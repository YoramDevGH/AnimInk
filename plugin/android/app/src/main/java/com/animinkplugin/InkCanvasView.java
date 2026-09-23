package com.animinkplugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

final class InkCanvasView extends View {
    private static final String TAG = "AnimInkCanvas";
    private static final int MAX_PEN_LOAD_ATTEMPTS = 12;
    private static final int MAX_HISTORY = 24;
    static final int TOOL_PENCIL = 0;
    static final int TOOL_INK = 1;
    static final int TOOL_ERASER = 2;

    interface Listener {
        void onStrokeFinished();
        void onFrameSwipe(int direction);
        void onPlaybackFramePresented();
    }

    private static final float PAGE_RATIO = 16f / 9f;
    private static final int DESK_COLOR = Color.rgb(238, 238, 238);
    private final Paint displayPaint = new Paint();
    private final Paint strokePaint = AnimationProject.createStrokePaint();
    private final Paint fillPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF page = new RectF();
    private final RectF dirtyLogical = new RectF();
    private final RectF tileTarget = new RectF();
    private final Rect pageRect = new Rect();
    private final Rect bitmapSource = new Rect();
    private final Rect tileDestination = new Rect();
    private final Path lassoViewPath = new Path();
    private final Path lassoLogicalPath = new Path();
    private final Bitmap sketchCaptureBitmap = Bitmap.createBitmap(AnimationProject.TILE_SIZE,
            AnimationProject.TILE_SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas sketchCaptureCanvas = new Canvas(sketchCaptureBitmap);
    private final int[] sketchSourcePixels = new int[AnimationProject.TILE_SIZE
            * AnimationProject.TILE_SIZE];
    private final int[] sketchTargetPixels = new int[AnimationProject.TILE_SIZE
            * AnimationProject.TILE_SIZE];
    private final RectF lassoViewBounds = new RectF();
    private final RectF lassoLogicalBounds = new RectF();
    private final ArrayDeque<HistoryEntry> undoHistory = new ArrayDeque<>();
    private final ArrayDeque<HistoryEntry> redoHistory = new ArrayDeque<>();
    private final SupernotePenEngine penEngine;
    private AnimationProject project;
    private Listener listener;
    private int frameIndex;
    private int activeLayer = AnimationProject.LAYER_SKETCH;
    private int drawingLayer = AnimationProject.LAYER_SKETCH;
    private int tool = TOOL_PENCIL;
    private boolean playing;
    private boolean playbackFramePending;
    private boolean active = true;
    private boolean drawingStroke;
    private boolean hasPreviousPoint;
    private boolean forwardingPenStroke;
    private float previousX;
    private float previousY;
    private float previousPressure;
    private HistoryEntry fallbackHistory;
    private boolean lassoSelecting;
    private int lassoPointCount;
    private int gestureFingerCount;
    private boolean singleFingerGesture;
    private float gestureStartX;
    private float gestureStartY;
    private float gestureCurrentX;
    private float gestureCurrentY;
    private long gestureStartTime;
    private int penLoadGeneration;

    InkCanvasView(Context context) { this(context, null); }
    InkCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setBackgroundColor(DESK_COLOR);
        displayPaint.setFilterBitmap(false);
        displayPaint.setDither(false);
        fillPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setColor(Color.BLACK);
        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setStrokeWidth(3f);
        lassoPaint.setColor(Color.BLACK);
        lassoPaint.setPathEffect(new DashPathEffect(new float[]{12f, 8f}, 0f));
        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        penEngine = SupernotePenEngine.create(this, this::onHardwareStrokeFinished);
        if (penEngine != null) penEngine.setTool(tool, Color.BLACK);
    }

    void setProject(AnimationProject value) {
        commitCurrentFrame();
        clearHistory();
        project = value;
        frameIndex = 0;
        activeLayer = AnimationProject.LAYER_SKETCH;
        drawingStroke = false;
        resetLasso();
        ensureCurrentFrameRaster();
        invalidate();
        post(this::loadCurrentFrameIntoPenEngine);
    }

    void setListener(Listener value) { listener = value; }
    int getFrameIndex() { return frameIndex; }
    int getTool() { return tool; }
    int getActiveLayer() { return activeLayer; }
    boolean isLayerVisible(int layer) { return project != null && project.isLayerVisible(layer); }

    void setActiveLayer(int layer) {
        int bounded = layer == AnimationProject.LAYER_SKETCH
                ? AnimationProject.LAYER_SKETCH : AnimationProject.LAYER_FINAL;
        if (bounded == activeLayer) return;
        commitCurrentFrame();
        activeLayer = bounded;
        clearHistory();
        resetLasso();
        invalidate();
        configurePenForCurrentMode(true);
    }

    void setLayerVisible(int layer, boolean visible) {
        if (project == null || project.isLayerVisible(layer) == visible) return;
        commitCurrentFrame();
        project.setLayerVisible(layer, visible);
        invalidate();
        configurePenForCurrentMode(layer == activeLayer && visible);
    }

    void setTool(int value) {
        int next = Math.max(TOOL_PENCIL, Math.min(TOOL_ERASER, value));
        if (next == tool) return;
        commitCurrentFrame();
        tool = next;
        resetLasso();
        configurePenForCurrentMode(next != TOOL_ERASER);
    }

    private void configurePenForCurrentMode(boolean reload) {
        if (penEngine == null || project == null) return;
        boolean enabled = canUseNativePen();
        penEngine.setEnabled(enabled);
        penEngine.setBitmapVisible(enabled);
        if (enabled) {
            // Real gray forces a slower grayscale e-ink path. PW always previews
            // in black; sketch strokes are converted to gray after pen-up.
            penEngine.setTool(tool, Color.BLACK);
            if (reload) loadCurrentFrameIntoPenEngine();
        }
    }

    private boolean canUseNativePen() {
        return active && !playing && tool != TOOL_ERASER && project != null
                && project.isLayerVisible(activeLayer);
    }

    void setFrameIndex(int value) {
        if (project == null || project.frames.isEmpty()) return;
        int bounded = Math.max(0, Math.min(project.frames.size() - 1, value));
        if (bounded == frameIndex) return;
        commitCurrentFrame();
        frameIndex = bounded;
        drawingStroke = false;
        clearHistory();
        resetLasso();
        ensureCurrentFrameRaster();
        invalidate();
        loadCurrentFrameIntoPenEngine();
    }

    void setPlaying(boolean value) {
        if (value) commitCurrentFrame();
        playing = value;
        playbackFramePending = false;
        drawingStroke = false;
        resetLasso();
        if (penEngine != null) {
            penEngine.setEnabled(canUseNativePen());
            if (value) penEngine.setBitmapVisible(false);
        }
        if (!value) configurePenForCurrentMode(true);
    }

    void presentPlaybackFrame(int value) {
        if (!playing || project == null || project.frames.isEmpty()) return;
        frameIndex = Math.max(0, Math.min(project.frames.size() - 1, value));
        ensureCurrentFrameRaster();
        playbackFramePending = true;
        invalidate();
    }

    void setActive(boolean value) {
        active = value;
        if (penEngine != null) penEngine.setEnabled(canUseNativePen());
        if (value && !playing) configurePenForCurrentMode(true);
    }

    void rebuildCurrentFrame() {
        clearHistory();
        invalidate();
        loadCurrentFrameIntoPenEngine();
    }

    void commitCurrentFrame() {
        if (penEngine == null || project == null || project.frames.isEmpty() || getWidth() == 0) return;
        Rect pendingDirty = penEngine.getPendingDirtyRect();
        if (!penEngine.isWriting() && pendingDirty == null) return;
        Bitmap bitmap = penEngine.getBitmap();
        if (bitmap != null && !bitmap.isRecycled())
            captureNativeStroke(bitmap, pendingDirty, drawingLayer);
    }

    boolean isUsingSupernotePenEngine() { return penEngine != null; }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        updatePage(width, height, page);
        page.round(pageRect);
        if (penEngine != null) {
            penEngine.setWritableRect(pageRect);
            post(this::loadCurrentFrameIntoPenEngine);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(DESK_COLOR);
        fillPaint.setColor(Color.WHITE);
        canvas.drawRect(page, fillPaint);
        if (project != null && !project.frames.isEmpty()) {
            ensureCurrentFrameRaster();
            AnimationProject.DrawingFrame frame = project.frames.get(frameIndex);
            if (project.sketchVisible) drawLayer(canvas, frame.layer(AnimationProject.LAYER_SKETCH));
            if (project.finalVisible) drawLayer(canvas, frame.layer(AnimationProject.LAYER_FINAL));
        }
        canvas.drawRect(page, borderPaint);
        if (lassoSelecting) canvas.drawPath(lassoViewPath, lassoPaint);
        if (playing && playbackFramePending) {
            playbackFramePending = false;
            // Use the same non-flashing GLUI presentation as ordinary UI
            // changes. A forced full update flashes black on every frame.
            if (penEngine != null) penEngine.refreshPageWithoutFlash(pageRect);
            post(() -> {
                if (playing && listener != null) listener.onPlaybackFramePresented();
            });
        }
    }

    private void drawLayer(Canvas canvas, AnimationProject.RasterLayer layer) {
        float sx = page.width() / project.canvasWidth;
        float sy = page.height() / project.canvasHeight;
        for (AnimationProject.RasterTile tile : layer.tiles.values()) {
            float left = page.left + tile.x * AnimationProject.TILE_SIZE * sx;
            float top = page.top + tile.y * AnimationProject.TILE_SIZE * sy;
            tileTarget.set(left, top,
                    Math.min(page.right, left + AnimationProject.TILE_SIZE * sx),
                    Math.min(page.bottom, top + AnimationProject.TILE_SIZE * sy));
            canvas.drawBitmap(tile.bitmap, null, tileTarget, displayPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (playing || project == null || project.frames.isEmpty() || page.width() <= 0f) return false;
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            singleFingerGesture = true;
            gestureStartX = event.getX();
            gestureStartY = event.getY();
            gestureCurrentX = gestureStartX;
            gestureCurrentY = gestureStartY;
            gestureStartTime = event.getEventTime();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && (event.getPointerCount() == 2
                || event.getPointerCount() == 3) && allPointersAreFingers(event)) {
            singleFingerGesture = false;
            startOrExtendFingerGesture(event);
            return true;
        }
        if (gestureFingerCount > 0) {
            if (action == MotionEvent.ACTION_MOVE) {
                gestureCurrentX = averageCoordinate(event, -1, true);
                gestureCurrentY = averageCoordinate(event, -1, false);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                // Do not recompute a centroid after removing the lifted finger:
                // that point can jump by half the distance between the fingers
                // and incorrectly turn every tap into a swipe.
                finishFingerGesture();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishFingerGesture();
                return true;
            }
            return true;
        }

        if (singleFingerGesture) {
            if (action == MotionEvent.ACTION_MOVE) {
                gestureCurrentX = event.getX();
                gestureCurrentY = event.getY();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishSingleFingerGesture(action == MotionEvent.ACTION_UP);
                return true;
            }
            return true;
        }

        int pointerIndex = Math.max(0, event.getActionIndex());
        int inputTool = event.getToolType(pointerIndex);
        boolean stylus = inputTool == MotionEvent.TOOL_TYPE_STYLUS
                || inputTool == MotionEvent.TOOL_TYPE_ERASER;
        if (!stylus) {
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        if (tool == TOOL_ERASER || inputTool == MotionEvent.TOOL_TYPE_ERASER)
            return handleLassoStylus(event);
        if (!project.isLayerVisible(activeLayer)) return true;

        if (penEngine != null) {
            if (action == MotionEvent.ACTION_DOWN) {
                drawingLayer = activeLayer;
                forwardingPenStroke = !penEngine.isWriting();
                if (forwardingPenStroke) {
                    Log.d(TAG, "Use host stylus routing for device=" + event.getDeviceId());
                    penEngine.forwardEvent(event);
                }
            } else if (forwardingPenStroke) {
                penEngine.forwardEvent(event);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    forwardingPenStroke = false;
            }
            return true;
        }
        return handleFallbackStylus(event);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private boolean handleFallbackStylus(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!page.contains(event.getX(), event.getY())) return false;
                drawingStroke = true;
                drawingLayer = activeLayer;
                hasPreviousPoint = false;
                fallbackHistory = snapshotLogical(new RectF(0f, 0f,
                        project.canvasWidth, project.canvasHeight), drawingLayer);
                dirtyLogical.setEmpty();
                addFallbackPoint(event.getX(), event.getY(), event.getPressure());
                invalidateLogicalDirty();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!drawingStroke) return false;
                dirtyLogical.setEmpty();
                for (int i = 0; i < event.getHistorySize(); i++)
                    addFallbackPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i));
                addFallbackPoint(event.getX(), event.getY(), event.getPressure());
                invalidateLogicalDirty();
                return true;
            case MotionEvent.ACTION_UP:
                if (!drawingStroke) return false;
                dirtyLogical.setEmpty();
                addFallbackPoint(event.getX(), event.getY(), event.getPressure());
                invalidateLogicalDirty();
                drawingStroke = false;
                pushHistory(fallbackHistory);
                fallbackHistory = null;
                if (listener != null) listener.onStrokeFinished();
                return true;
            case MotionEvent.ACTION_CANCEL:
                drawingStroke = false;
                recycleHistoryEntry(fallbackHistory);
                fallbackHistory = null;
                return true;
            default:
                return false;
        }
    }

    private void addFallbackPoint(float viewX, float viewY, float rawPressure) {
        float x = viewToLogicalX(viewX);
        float y = viewToLogicalY(viewY);
        float pressure = rawPressure <= 0f ? 0.5f : clamp(rawPressure, 0.05f, 1f);
        if (hasPreviousPoint) {
            float dx = x - previousX;
            float dy = y - previousY;
            if (dx * dx + dy * dy < 0.35f) return;
        }
        float oldX = hasPreviousPoint ? previousX : x;
        float oldY = hasPreviousPoint ? previousY : y;
        float averagePressure = hasPreviousPoint ? (previousPressure + pressure) * 0.5f : pressure;
        float width = AnimationProject.segmentWidth(toolWidth(), averagePressure, project.canvasWidth);
        project.drawSegment(currentFrame().layer(drawingLayer), oldX, oldY, x, y,
                width, project.layerColor(drawingLayer), false, strokePaint);
        float radius = width * 0.5f + 3f;
        if (dirtyLogical.isEmpty()) dirtyLogical.set(oldX - radius, oldY - radius, x + radius, y + radius);
        else dirtyLogical.union(Math.min(oldX, x) - radius, Math.min(oldY, y) - radius,
                Math.max(oldX, x) + radius, Math.max(oldY, y) + radius);
        previousX = x;
        previousY = y;
        previousPressure = pressure;
        hasPreviousPoint = true;
    }

    private boolean handleLassoStylus(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!page.contains(event.getX(), event.getY())) return false;
                lassoSelecting = true;
                lassoPointCount = 0;
                lassoViewPath.reset();
                lassoLogicalPath.reset();
                lassoViewBounds.setEmpty();
                lassoLogicalBounds.setEmpty();
                addLassoPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!lassoSelecting) return false;
                for (int i = 0; i < event.getHistorySize(); i++)
                    addLassoPoint(event.getHistoricalX(i), event.getHistoricalY(i));
                addLassoPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (!lassoSelecting) return false;
                addLassoPoint(event.getX(), event.getY());
                if (lassoPointCount >= 3) {
                    lassoViewPath.close();
                    lassoLogicalPath.close();
                    eraseLassoSelection();
                }
                resetLasso();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetLasso();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void addLassoPoint(float rawX, float rawY) {
        float viewX = clamp(rawX, page.left, page.right);
        float viewY = clamp(rawY, page.top, page.bottom);
        float logicalX = viewToLogicalX(viewX);
        float logicalY = viewToLogicalY(viewY);
        if (lassoPointCount == 0) {
            lassoViewPath.moveTo(viewX, viewY);
            lassoLogicalPath.moveTo(logicalX, logicalY);
            lassoViewBounds.set(viewX, viewY, viewX, viewY);
            lassoLogicalBounds.set(logicalX, logicalY, logicalX, logicalY);
        } else {
            lassoViewPath.lineTo(viewX, viewY);
            lassoLogicalPath.lineTo(logicalX, logicalY);
            lassoViewBounds.union(viewX, viewY);
            lassoLogicalBounds.union(logicalX, logicalY);
        }
        lassoPointCount++;
        invalidate((int) lassoViewBounds.left - 8, (int) lassoViewBounds.top - 8,
                (int) lassoViewBounds.right + 8, (int) lassoViewBounds.bottom + 8);
    }

    private void eraseLassoSelection() {
        HistoryEntry before = snapshotLogical(lassoLogicalBounds, activeLayer);
        AnimationProject.RasterLayer layer = currentFrame().layer(activeLayer);
        int minX = tileMin(lassoLogicalBounds.left, project.tileColumns());
        int minY = tileMin(lassoLogicalBounds.top, project.tileRows());
        int maxX = tileMax(lassoLogicalBounds.right, project.tileColumns());
        int maxY = tileMax(lassoLogicalBounds.bottom, project.tileRows());
        for (int tileY = minY; tileY <= maxY; tileY++) {
            for (int tileX = minX; tileX <= maxX; tileX++) {
                AnimationProject.RasterTile tile = layer.tiles.get(AnimationProject.tileKey(tileX, tileY));
                if (tile == null) continue;
                int save = tile.canvas.save();
                tile.canvas.translate(-tileX * AnimationProject.TILE_SIZE,
                        -tileY * AnimationProject.TILE_SIZE);
                tile.canvas.drawPath(lassoLogicalPath, clearPaint);
                tile.canvas.restoreToCount(save);
            }
        }
        pushHistory(before);
        loadCurrentFrameIntoPenEngine();
        if (listener != null) listener.onStrokeFinished();
    }

    private void resetLasso() {
        lassoSelecting = false;
        lassoPointCount = 0;
        lassoViewPath.reset();
        lassoLogicalPath.reset();
        lassoViewBounds.setEmpty();
        lassoLogicalBounds.setEmpty();
    }

    private void invalidateLogicalDirty() {
        if (dirtyLogical.isEmpty()) return;
        float sx = page.width() / project.canvasWidth;
        float sy = page.height() / project.canvasHeight;
        invalidate((int) (page.left + dirtyLogical.left * sx) - 2,
                (int) (page.top + dirtyLogical.top * sy) - 2,
                (int) (page.left + dirtyLogical.right * sx) + 2,
                (int) (page.top + dirtyLogical.bottom * sy) + 2);
    }

    private void onHardwareStrokeFinished(Bitmap bitmap, Rect dirtyRect) {
        if (project == null || playing || bitmap == null || bitmap.isRecycled()) return;
        Log.d(TAG, "Synchronize native layer=" + drawingLayer + " raster=" + dirtyRect);
        RectF logical = logicalRectFromView(dirtyRect);
        HistoryEntry before = logical == null ? null : snapshotLogical(logical, drawingLayer);
        captureNativeStroke(bitmap, dirtyRect, drawingLayer);
        pushHistory(before);
        invalidate(dirtyRect);
        if (listener != null) listener.onStrokeFinished();
    }

    private void captureNativeStroke(Bitmap bitmap, Rect dirtyView, int layerIndex) {
        if (layerIndex == AnimationProject.LAYER_SKETCH) {
            captureSketchStroke(bitmap, dirtyView);
            // The PW layer contains only the just-finished black preview. Clear
            // that small area so the stable gray raster underneath is revealed.
            if (penEngine != null) penEngine.clearTransient(dirtyView);
        } else {
            capturePenBitmap(bitmap, dirtyView, layerIndex);
        }
    }

    private void captureSketchStroke(Bitmap bitmap, Rect dirtyView) {
        if (page.width() <= 0f) return;
        RectF logical = dirtyView == null
                ? new RectF(0f, 0f, project.canvasWidth, project.canvasHeight)
                : logicalRectFromView(dirtyView);
        if (logical == null) return;
        int minX = tileMin(logical.left, project.tileColumns());
        int minY = tileMin(logical.top, project.tileRows());
        int maxX = tileMax(logical.right, project.tileColumns());
        int maxY = tileMax(logical.bottom, project.tileRows());
        AnimationProject.RasterLayer layer = currentFrame().layer(AnimationProject.LAYER_SKETCH);
        for (int tileY = minY; tileY <= maxY; tileY++) {
            for (int tileX = minX; tileX <= maxX; tileX++) {
                float logicalLeft = tileX * AnimationProject.TILE_SIZE;
                float logicalTop = tileY * AnimationProject.TILE_SIZE;
                bitmapSource.set(
                        Math.max(0, Math.round(page.left + logicalLeft * page.width() / project.canvasWidth)),
                        Math.max(0, Math.round(page.top + logicalTop * page.height() / project.canvasHeight)),
                        Math.min(bitmap.getWidth(), Math.round(page.left
                                + Math.min(project.canvasWidth, logicalLeft + AnimationProject.TILE_SIZE)
                                * page.width() / project.canvasWidth)),
                        Math.min(bitmap.getHeight(), Math.round(page.top
                                + Math.min(project.canvasHeight, logicalTop + AnimationProject.TILE_SIZE)
                                * page.height() / project.canvasHeight)));
                int tileWidth = Math.min(AnimationProject.TILE_SIZE,
                        project.canvasWidth - (int) logicalLeft);
                int tileHeight = Math.min(AnimationProject.TILE_SIZE,
                        project.canvasHeight - (int) logicalTop);
                tileDestination.set(0, 0, tileWidth, tileHeight);
                sketchCaptureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                sketchCaptureCanvas.drawBitmap(bitmap, bitmapSource, tileDestination, displayPaint);
                sketchCaptureBitmap.getPixels(sketchSourcePixels, 0, AnimationProject.TILE_SIZE,
                        0, 0, AnimationProject.TILE_SIZE, AnimationProject.TILE_SIZE);

                AnimationProject.RasterTile tile = layer.getOrCreateTile(tileX, tileY);
                tile.bitmap.getPixels(sketchTargetPixels, 0, AnimationProject.TILE_SIZE,
                        0, 0, AnimationProject.TILE_SIZE, AnimationProject.TILE_SIZE);
                for (int i = 0; i < sketchSourcePixels.length; i++) {
                    int source = sketchSourcePixels[i];
                    int alpha = Color.alpha(source);
                    int luminance = (Color.red(source) * 54 + Color.green(source) * 183
                            + Color.blue(source) * 19) >> 8;
                    int coverage = alpha * (255 - luminance) / 255;
                    if (coverage <= 8) continue;
                    int oldAlpha = Color.alpha(sketchTargetPixels[i]);
                    int mergedAlpha = 255 - (255 - oldAlpha) * (255 - coverage) / 255;
                    sketchTargetPixels[i] = Color.argb(mergedAlpha,
                            Color.red(AnimationProject.SKETCH_COLOR),
                            Color.green(AnimationProject.SKETCH_COLOR),
                            Color.blue(AnimationProject.SKETCH_COLOR));
                }
                tile.bitmap.setPixels(sketchTargetPixels, 0, AnimationProject.TILE_SIZE,
                        0, 0, AnimationProject.TILE_SIZE, AnimationProject.TILE_SIZE);
            }
        }
    }

    private void capturePenBitmap(Bitmap bitmap, Rect dirtyView, int layerIndex) {
        if (page.width() <= 0f) return;
        RectF logical = dirtyView == null
                ? new RectF(0f, 0f, project.canvasWidth, project.canvasHeight)
                : logicalRectFromView(dirtyView);
        if (logical == null) return;
        int minX = tileMin(logical.left, project.tileColumns());
        int minY = tileMin(logical.top, project.tileRows());
        int maxX = tileMax(logical.right, project.tileColumns());
        int maxY = tileMax(logical.bottom, project.tileRows());
        AnimationProject.RasterLayer layer = currentFrame().layer(layerIndex);
        for (int tileY = minY; tileY <= maxY; tileY++) {
            for (int tileX = minX; tileX <= maxX; tileX++) {
                AnimationProject.RasterTile tile = layer.getOrCreateTile(tileX, tileY);
                tile.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                float logicalLeft = tileX * AnimationProject.TILE_SIZE;
                float logicalTop = tileY * AnimationProject.TILE_SIZE;
                bitmapSource.set(
                        Math.max(0, Math.round(page.left + logicalLeft * page.width() / project.canvasWidth)),
                        Math.max(0, Math.round(page.top + logicalTop * page.height() / project.canvasHeight)),
                        Math.min(bitmap.getWidth(), Math.round(page.left
                                + Math.min(project.canvasWidth, logicalLeft + AnimationProject.TILE_SIZE)
                                * page.width() / project.canvasWidth)),
                        Math.min(bitmap.getHeight(), Math.round(page.top
                                + Math.min(project.canvasHeight, logicalTop + AnimationProject.TILE_SIZE)
                                * page.height() / project.canvasHeight)));
                tileDestination.set(0, 0,
                        Math.min(AnimationProject.TILE_SIZE, project.canvasWidth - (int) logicalLeft),
                        Math.min(AnimationProject.TILE_SIZE, project.canvasHeight - (int) logicalTop));
                tile.canvas.drawBitmap(bitmap, bitmapSource, tileDestination, displayPaint);
            }
        }
    }

    private RectF logicalRectFromView(Rect dirtyView) {
        RectF clipped = new RectF(dirtyView);
        if (!clipped.intersect(page)) return null;
        return new RectF(
                (clipped.left - page.left) * project.canvasWidth / page.width(),
                (clipped.top - page.top) * project.canvasHeight / page.height(),
                (clipped.right - page.left) * project.canvasWidth / page.width(),
                (clipped.bottom - page.top) * project.canvasHeight / page.height());
    }

    private void loadCurrentFrameIntoPenEngine() {
        int generation = ++penLoadGeneration;
        loadCurrentFrameIntoPenEngine(generation, 0);
    }

    private void loadCurrentFrameIntoPenEngine(int generation, int attempt) {
        if (generation != penLoadGeneration) return;
        if (penEngine == null || project == null || project.frames.isEmpty()
                || getWidth() <= 0 || getHeight() <= 0 || !canUseNativePen()) return;
        if (!isAttachedToWindow() || !penEngine.isReady()) {
            if (attempt < MAX_PEN_LOAD_ATTEMPTS)
                postDelayed(() -> loadCurrentFrameIntoPenEngine(generation, attempt + 1), 50L);
            else
                Log.w(TAG, "Native pen surface was not ready after retries");
            return;
        }
        ensureCurrentFrameRaster();
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        // Sketch is kept out of PW: existing gray pixels stay in the host view,
        // while PW contains only the current fast black preview stroke.
        if (activeLayer == AnimationProject.LAYER_FINAL)
            drawLayer(canvas, currentFrame().layer(activeLayer));
        penEngine.setWritableRect(pageRect);
        penEngine.loadBitmap(bitmap);
        penEngine.setBitmapVisible(true);
        penEngine.setTool(tool, Color.BLACK);
    }

    private HistoryEntry snapshotLogical(RectF logical, int layerIndex) {
        if (project == null || logical == null || logical.isEmpty()) return null;
        HistoryEntry entry = new HistoryEntry();
        entry.layer = layerIndex;
        int minX = tileMin(logical.left, project.tileColumns());
        int minY = tileMin(logical.top, project.tileRows());
        int maxX = tileMax(logical.right, project.tileColumns());
        int maxY = tileMax(logical.bottom, project.tileRows());
        AnimationProject.RasterLayer layer = currentFrame().layer(layerIndex);
        for (int tileY = minY; tileY <= maxY; tileY++) {
            for (int tileX = minX; tileX <= maxX; tileX++) {
                int key = AnimationProject.tileKey(tileX, tileY);
                AnimationProject.RasterTile tile = layer.tiles.get(key);
                entry.tiles.put(key, tile == null ? null : cloneBitmap(tile.bitmap));
            }
        }
        return entry;
    }

    private HistoryEntry snapshotKeys(HistoryEntry source) {
        HistoryEntry result = new HistoryEntry();
        result.layer = source.layer;
        AnimationProject.RasterLayer layer = currentFrame().layer(source.layer);
        for (int key : source.tiles.keySet()) {
            AnimationProject.RasterTile tile = layer.tiles.get(key);
            result.tiles.put(key, tile == null ? null : cloneBitmap(tile.bitmap));
        }
        return result;
    }

    private void pushHistory(HistoryEntry entry) {
        if (entry == null || entry.tiles.isEmpty()) {
            recycleHistoryEntry(entry);
            return;
        }
        undoHistory.addLast(entry);
        while (undoHistory.size() > MAX_HISTORY) recycleHistoryEntry(undoHistory.removeFirst());
        clearStack(redoHistory);
    }

    private boolean undo() { return applyHistory(undoHistory, redoHistory); }
    private boolean redo() { return applyHistory(redoHistory, undoHistory); }

    private boolean applyHistory(ArrayDeque<HistoryEntry> source, ArrayDeque<HistoryEntry> destination) {
        if (source.isEmpty()) return false;
        HistoryEntry entry = source.removeLast();
        HistoryEntry reverse = snapshotKeys(entry);
        AnimationProject.RasterLayer layer = currentFrame().layer(entry.layer);
        for (Map.Entry<Integer, Bitmap> state : entry.tiles.entrySet()) {
            int key = state.getKey();
            layer.removeTile(key);
            Bitmap saved = state.getValue();
            if (saved != null) {
                AnimationProject.RasterTile tile = layer.getOrCreateTile(
                        AnimationProject.tileX(key), AnimationProject.tileY(key));
                tile.canvas.drawBitmap(saved, 0f, 0f, null);
            }
        }
        recycleHistoryEntry(entry);
        destination.addLast(reverse);
        invalidate();
        loadCurrentFrameIntoPenEngine();
        if (listener != null) listener.onStrokeFinished();
        return true;
    }

    private void clearHistory() {
        clearStack(undoHistory);
        clearStack(redoHistory);
        recycleHistoryEntry(fallbackHistory);
        fallbackHistory = null;
    }

    private static void clearStack(ArrayDeque<HistoryEntry> stack) {
        while (!stack.isEmpty()) recycleHistoryEntry(stack.removeFirst());
    }

    private static void recycleHistoryEntry(HistoryEntry entry) {
        if (entry == null) return;
        for (Bitmap bitmap : entry.tiles.values())
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        entry.tiles.clear();
    }

    private static Bitmap cloneBitmap(Bitmap source) {
        return source.copy(Bitmap.Config.ARGB_8888, false);
    }

    private void startOrExtendFingerGesture(MotionEvent event) {
        gestureFingerCount = Math.max(gestureFingerCount, event.getPointerCount());
        gestureStartX = averageCoordinate(event, -1, true);
        gestureStartY = averageCoordinate(event, -1, false);
        gestureCurrentX = gestureStartX;
        gestureCurrentY = gestureStartY;
        gestureStartTime = event.getEventTime();
        drawingStroke = false;
        forwardingPenStroke = false;
        resetLasso();
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void finishFingerGesture() {
        if (gestureFingerCount == 0) return;
        float dx = gestureCurrentX - gestureStartX;
        float dy = gestureCurrentY - gestureStartY;
        float density = getResources().getDisplayMetrics().density;
        boolean tap = Math.hypot(dx, dy) <= density * 24f
                && android.os.SystemClock.uptimeMillis() - gestureStartTime <= 650L;
        int fingers = gestureFingerCount;
        gestureFingerCount = 0;
        if (tap) {
            if (fingers == 2) Log.d(TAG, "Two-finger tap: undo=" + undo());
            else if (fingers >= 3) Log.d(TAG, "Three-finger tap: redo=" + redo());
        } else if (fingers == 2 && Math.abs(dx) >= density * 72f
                && Math.abs(dx) > Math.abs(dy) && listener != null) {
            listener.onFrameSwipe(dx < 0f ? 1 : -1);
        }
    }

    private void finishSingleFingerGesture(boolean completed) {
        float dx = gestureCurrentX - gestureStartX;
        float dy = gestureCurrentY - gestureStartY;
        singleFingerGesture = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        float density = getResources().getDisplayMetrics().density;
        if (completed && Math.abs(dx) >= density * 56f
                && Math.abs(dx) > Math.abs(dy) && listener != null)
            listener.onFrameSwipe(dx < 0f ? 1 : -1);
    }

    private boolean allPointersAreFingers(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++)
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) return false;
        return true;
    }

    private float averageCoordinate(MotionEvent event, int ignoredIndex, boolean xAxis) {
        float sum = 0f;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == ignoredIndex) continue;
            sum += xAxis ? event.getX(i) : event.getY(i);
            count++;
        }
        return count == 0 ? (xAxis ? gestureCurrentX : gestureCurrentY) : sum / count;
    }

    private AnimationProject.DrawingFrame currentFrame() { return project.frames.get(frameIndex); }

    private void ensureCurrentFrameRaster() {
        if (project != null && !project.frames.isEmpty()) project.migrateLegacy(currentFrame());
    }

    private float viewToLogicalX(float viewX) {
        return clamp((viewX - page.left) * project.canvasWidth / page.width(), 0f, project.canvasWidth - 1f);
    }

    private float viewToLogicalY(float viewY) {
        return clamp((viewY - page.top) * project.canvasHeight / page.height(), 0f, project.canvasHeight - 1f);
    }

    private static int tileMin(float value, int count) {
        return Math.max(0, Math.min(count - 1, (int) Math.floor(value / AnimationProject.TILE_SIZE)));
    }

    private static int tileMax(float value, int count) {
        return Math.max(0, Math.min(count - 1,
                (int) Math.floor(Math.max(0f, value - 0.001f) / AnimationProject.TILE_SIZE)));
    }

    private float toolWidth() { return tool == TOOL_INK ? 0.0075f : 0.0032f; }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void updatePage(int width, int height, RectF target) {
        float availableRatio = width / (float) Math.max(1, height);
        if (availableRatio > PAGE_RATIO) {
            float pageWidth = height * PAGE_RATIO;
            float left = (width - pageWidth) * 0.5f;
            target.set(left, 0f, left + pageWidth, height);
        } else {
            float pageHeight = width / PAGE_RATIO;
            float top = (height - pageHeight) * 0.5f;
            target.set(0f, top, width, top + pageHeight);
        }
    }

    @Override protected void onDetachedFromWindow() {
        clearHistory();
        if (penEngine != null) penEngine.close();
        super.onDetachedFromWindow();
    }

    private static final class HistoryEntry {
        int layer;
        final Map<Integer, Bitmap> tiles = new LinkedHashMap<>();
    }
}
