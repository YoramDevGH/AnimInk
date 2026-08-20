package com.illou.animink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AnimationProject {
    static final int DEFAULT_CANVAS_WIDTH = 1536;
    static final int DEFAULT_CANVAS_HEIGHT = 864;
    static final int TILE_SIZE = 128;
    static final int TILE_BYTES = TILE_SIZE * TILE_SIZE * 4;
    static final int LAYER_SKETCH = 0;
    static final int LAYER_FINAL = 1;
    static final int LAYER_COUNT = 2;
    static final int SKETCH_COLOR = Color.rgb(145, 145, 145);
    static final int FINAL_COLOR = Color.BLACK;

    int fps = 4;
    int canvasWidth = DEFAULT_CANVAS_WIDTH;
    int canvasHeight = DEFAULT_CANVAS_HEIGHT;
    boolean sketchVisible = true;
    boolean finalVisible = true;
    final List<DrawingFrame> frames = new ArrayList<>();

    AnimationProject() {
        frames.add(new DrawingFrame());
    }

    boolean isLayerVisible(int layer) {
        return layer == LAYER_SKETCH ? sketchVisible : finalVisible;
    }

    void setLayerVisible(int layer, boolean visible) {
        if (layer == LAYER_SKETCH) sketchVisible = visible;
        else finalVisible = visible;
    }

    int layerColor(int layer) {
        return layer == LAYER_SKETCH ? SKETCH_COLOR : FINAL_COLOR;
    }

    void migrateAllLegacy() {
        for (DrawingFrame frame : frames) migrateLegacy(frame);
    }

    void migrateLegacy(DrawingFrame frame) {
        if (frame.legacyStrokes.isEmpty()) return;
        Paint paint = createStrokePaint();
        for (InkStroke stroke : frame.legacyStrokes) {
            InkPoint previous = null;
            for (InkPoint point : stroke.points) {
                float x = point.x * canvasWidth;
                float y = point.y * canvasHeight;
                float oldX = previous == null ? x : previous.x * canvasWidth;
                float oldY = previous == null ? y : previous.y * canvasHeight;
                float pressure = previous == null
                        ? point.pressure : (previous.pressure + point.pressure) * 0.5f;
                float width = segmentWidth(stroke.width, pressure, canvasWidth);
                drawSegment(frame.layer(LAYER_FINAL), oldX, oldY, x, y,
                        width, FINAL_COLOR, stroke.eraser, paint);
                previous = point;
            }
        }
        frame.legacyStrokes.clear();
    }

    void drawSegment(RasterLayer layer,
                     float oldX, float oldY, float x, float y,
                     float width, int color, boolean eraser, Paint paint) {
        float radius = width * 0.5f + 2f;
        int minTileX = clampTile((int) Math.floor((Math.min(oldX, x) - radius) / TILE_SIZE), tileColumns());
        int maxTileX = clampTile((int) Math.floor((Math.max(oldX, x) + radius) / TILE_SIZE), tileColumns());
        int minTileY = clampTile((int) Math.floor((Math.min(oldY, y) - radius) / TILE_SIZE), tileRows());
        int maxTileY = clampTile((int) Math.floor((Math.max(oldY, y) + radius) / TILE_SIZE), tileRows());

        paint.setXfermode(eraser ? new PorterDuffXfermode(PorterDuff.Mode.CLEAR) : null);
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1f, width));
        for (int tileY = minTileY; tileY <= maxTileY; tileY++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                RasterTile tile = layer.getOrCreateTile(tileX, tileY);
                float offsetX = tileX * TILE_SIZE;
                float offsetY = tileY * TILE_SIZE;
                if (oldX == x && oldY == y)
                    tile.canvas.drawPoint(x - offsetX, y - offsetY, paint);
                else
                    tile.canvas.drawLine(oldX - offsetX, oldY - offsetY,
                            x - offsetX, y - offsetY, paint);
            }
        }
        paint.setXfermode(null);
    }

    int tileColumns() { return (canvasWidth + TILE_SIZE - 1) / TILE_SIZE; }
    int tileRows() { return (canvasHeight + TILE_SIZE - 1) / TILE_SIZE; }

    static Paint createStrokePaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setDither(false);
        return paint;
    }

    static float segmentWidth(float normalizedWidth, float pressure, float pixelWidth) {
        return Math.max(1f, normalizedWidth * pixelWidth * (0.35f + pressure * 0.9f));
    }

    private static int clampTile(int value, int count) {
        return Math.max(0, Math.min(count - 1, value));
    }

    static int tileKey(int x, int y) { return (y << 16) | (x & 0xffff); }
    static int tileX(int key) { return key & 0xffff; }
    static int tileY(int key) { return key >>> 16; }

    static final class DrawingFrame {
        private final RasterLayer[] layers = {new RasterLayer(), new RasterLayer()};
        // Version 1 projects are converted once into the final layer.
        final List<InkStroke> legacyStrokes = new ArrayList<>();

        RasterLayer layer(int index) { return layers[index == LAYER_SKETCH ? LAYER_SKETCH : LAYER_FINAL]; }

        void clear() {
            for (RasterLayer layer : layers) layer.clear();
            legacyStrokes.clear();
        }

        DrawingFrame copy() {
            DrawingFrame result = new DrawingFrame();
            result.layers[LAYER_SKETCH].replaceWith(layers[LAYER_SKETCH]);
            result.layers[LAYER_FINAL].replaceWith(layers[LAYER_FINAL]);
            for (InkStroke stroke : legacyStrokes) result.legacyStrokes.add(stroke.copy());
            return result;
        }
    }

    static final class RasterLayer {
        final Map<Integer, RasterTile> tiles = new LinkedHashMap<>();

        RasterTile getOrCreateTile(int x, int y) {
            int key = tileKey(x, y);
            RasterTile tile = tiles.get(key);
            if (tile == null) {
                tile = new RasterTile(x, y);
                tiles.put(key, tile);
            }
            return tile;
        }

        void removeTile(int key) {
            RasterTile tile = tiles.remove(key);
            if (tile != null && !tile.bitmap.isRecycled()) tile.bitmap.recycle();
        }

        void clear() {
            for (RasterTile tile : tiles.values()) tile.bitmap.recycle();
            tiles.clear();
        }

        void replaceWith(RasterLayer source) {
            clear();
            for (RasterTile tile : source.tiles.values()) {
                RasterTile clone = new RasterTile(tile.x, tile.y);
                clone.canvas.drawBitmap(tile.bitmap, 0f, 0f, null);
                tiles.put(tileKey(clone.x, clone.y), clone);
            }
        }
    }

    static final class RasterTile {
        final int x;
        final int y;
        final Bitmap bitmap;
        final Canvas canvas;

        RasterTile(int x, int y) {
            this.x = x;
            this.y = y;
            bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
    }

    static final class InkStroke {
        boolean eraser;
        float width;
        final List<InkPoint> points = new ArrayList<>();

        InkStroke copy() {
            InkStroke result = new InkStroke();
            result.eraser = eraser;
            result.width = width;
            for (InkPoint point : points) result.points.add(new InkPoint(point.x, point.y, point.pressure));
            return result;
        }
    }

    static final class InkPoint {
        final float x;
        final float y;
        final float pressure;

        InkPoint(float x, float y, float pressure) {
            this.x = x;
            this.y = y;
            this.pressure = pressure;
        }
    }
}
