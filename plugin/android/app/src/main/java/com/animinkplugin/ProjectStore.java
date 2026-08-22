package com.animinkplugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.AtomicFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

final class ProjectStore {
    private static final int MAGIC = 0x41494E4B; // AINK
    private static final int VERSION_VECTOR = 1;
    private static final int VERSION_RASTER_TILES = 2;
    private static final int VERSION_TWO_LAYERS = 3;
    private static final int LEGACY_TILE_BYTES = AnimationProject.TILE_SIZE * AnimationProject.TILE_SIZE * 2;
    private static final int MAX_FRAMES = 500;
    private static final int MAX_STROKES = 200_000;
    private static final int MAX_POINTS = 2_000_000;
    private static final int MAX_TILES_PER_LAYER = 4096;

    private ProjectStore() {}

    static AnimationProject loadRecovery(Context context) {
        AtomicFile file = recoveryFile(context);
        File source = file.getBaseFile();
        if (!source.exists()) {
            File legacy = new File(context.getFilesDir(), "animink_plugin_autosave.aink");
            if (!legacy.exists()) return new AnimationProject();
            source = legacy;
        }
        try (InputStream input = new java.io.FileInputStream(source)) {
            return load(input);
        } catch (IOException error) {
            return new AnimationProject();
        }
    }

    static AnimationProject load(InputStream source) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(source))) {
            if (input.readInt() != MAGIC) throw new IOException("Unrecognized AnimInk format");
            int version = input.readInt();
            if (version == VERSION_VECTOR) return loadVector(input);
            if (version == VERSION_RASTER_TILES) return loadSingleRaster(input);
            if (version == VERSION_TWO_LAYERS) return loadTwoLayers(input);
            throw new IOException("Unrecognized AnimInk version");
        }
    }

    private static AnimationProject loadVector(DataInputStream input) throws IOException {
        AnimationProject project = emptyProject(input.readInt());
        int frameCount = checked(input.readInt(), MAX_FRAMES);
        int totalStrokes = 0;
        int totalPoints = 0;
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            AnimationProject.DrawingFrame frame = new AnimationProject.DrawingFrame();
            int strokeCount = checked(input.readInt(), MAX_STROKES - totalStrokes);
            totalStrokes += strokeCount;
            for (int strokeIndex = 0; strokeIndex < strokeCount; strokeIndex++) {
                AnimationProject.InkStroke stroke = new AnimationProject.InkStroke();
                stroke.eraser = input.readBoolean();
                stroke.width = input.readFloat();
                int pointCount = checked(input.readInt(), MAX_POINTS - totalPoints);
                totalPoints += pointCount;
                for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
                    stroke.points.add(new AnimationProject.InkPoint(
                            input.readFloat(), input.readFloat(), input.readFloat()));
                }
                frame.legacyStrokes.add(stroke);
            }
            project.frames.add(frame);
        }
        ensureFrame(project);
        return project;
    }

    /** Version 2 had one opaque RGB_565 layer. It becomes the final layer. */
    private static AnimationProject loadSingleRaster(DataInputStream input) throws IOException {
        AnimationProject project = emptyProject(input.readInt());
        readDimensions(input, project);
        int columns = project.tileColumns();
        int rows = project.tileRows();
        int frameCount = checked(input.readInt(), MAX_FRAMES);
        byte[] tileBytes = new byte[LEGACY_TILE_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(tileBytes);
        Bitmap legacy = Bitmap.createBitmap(AnimationProject.TILE_SIZE,
                AnimationProject.TILE_SIZE, Bitmap.Config.RGB_565);
        int[] pixels = new int[AnimationProject.TILE_SIZE * AnimationProject.TILE_SIZE];
        try {
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                AnimationProject.DrawingFrame frame = new AnimationProject.DrawingFrame();
                int tileCount = checked(input.readInt(), Math.min(MAX_TILES_PER_LAYER, columns * rows));
                for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
                    int x = checked(input.readInt(), columns - 1);
                    int y = checked(input.readInt(), rows - 1);
                    if (input.readInt() != LEGACY_TILE_BYTES) throw new IOException("Invalid AnimInk v2 tile");
                    input.readFully(tileBytes);
                    buffer.rewind();
                    legacy.copyPixelsFromBuffer(buffer);
                    legacy.getPixels(pixels, 0, AnimationProject.TILE_SIZE, 0, 0,
                            AnimationProject.TILE_SIZE, AnimationProject.TILE_SIZE);
                    for (int i = 0; i < pixels.length; i++) {
                        int color = pixels[i];
                        if (Color.red(color) >= 248 && Color.green(color) >= 248 && Color.blue(color) >= 248)
                            pixels[i] = Color.TRANSPARENT;
                        else
                            pixels[i] = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
                    }
                    AnimationProject.RasterTile tile = frame.layer(AnimationProject.LAYER_FINAL)
                            .getOrCreateTile(x, y);
                    tile.bitmap.setPixels(pixels, 0, AnimationProject.TILE_SIZE, 0, 0,
                            AnimationProject.TILE_SIZE, AnimationProject.TILE_SIZE);
                }
                project.frames.add(frame);
            }
        } finally {
            legacy.recycle();
        }
        ensureFrame(project);
        return project;
    }

    private static AnimationProject loadTwoLayers(DataInputStream input) throws IOException {
        AnimationProject project = emptyProject(input.readInt());
        readDimensions(input, project);
        project.sketchVisible = input.readBoolean();
        project.finalVisible = input.readBoolean();
        int columns = project.tileColumns();
        int rows = project.tileRows();
        int frameCount = checked(input.readInt(), MAX_FRAMES);
        byte[] tileBytes = new byte[AnimationProject.TILE_BYTES];
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            AnimationProject.DrawingFrame frame = new AnimationProject.DrawingFrame();
            for (int layerIndex = 0; layerIndex < AnimationProject.LAYER_COUNT; layerIndex++) {
                int tileCount = checked(input.readInt(), Math.min(MAX_TILES_PER_LAYER, columns * rows));
                for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
                    int x = checked(input.readInt(), columns - 1);
                    int y = checked(input.readInt(), rows - 1);
                    if (input.readInt() != AnimationProject.TILE_BYTES)
                        throw new IOException("Invalid AnimInk v3 tile");
                    input.readFully(tileBytes);
                    AnimationProject.RasterTile tile = frame.layer(layerIndex).getOrCreateTile(x, y);
                    tile.bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(tileBytes));
                }
            }
            project.frames.add(frame);
        }
        ensureFrame(project);
        return project;
    }

    private static void readDimensions(DataInputStream input, AnimationProject project) throws IOException {
        project.canvasWidth = checkedRange(input.readInt(), 256, 4096);
        project.canvasHeight = checkedRange(input.readInt(), 256, 4096);
    }

    private static AnimationProject emptyProject(int fps) {
        AnimationProject project = new AnimationProject();
        project.frames.clear();
        project.fps = clamp(fps, 1, 12);
        return project;
    }

    private static void ensureFrame(AnimationProject project) {
        if (project.frames.isEmpty()) project.frames.add(new AnimationProject.DrawingFrame());
    }

    static void saveRecovery(Context context, AnimationProject project) throws IOException {
        AtomicFile file = recoveryFile(context);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            save(stream, project);
            file.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) file.failWrite(stream);
            throw error;
        }
    }

    static void save(OutputStream target, AnimationProject project) throws IOException {
        project.migrateAllLegacy();
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(target));
        output.writeInt(MAGIC);
        output.writeInt(VERSION_TWO_LAYERS);
        output.writeInt(project.fps);
        output.writeInt(project.canvasWidth);
        output.writeInt(project.canvasHeight);
        output.writeBoolean(project.sketchVisible);
        output.writeBoolean(project.finalVisible);
        output.writeInt(project.frames.size());
        ByteBuffer tileBuffer = ByteBuffer.allocate(AnimationProject.TILE_BYTES);
        for (AnimationProject.DrawingFrame frame : project.frames) {
            for (int layerIndex = 0; layerIndex < AnimationProject.LAYER_COUNT; layerIndex++) {
                AnimationProject.RasterLayer layer = frame.layer(layerIndex);
                output.writeInt(layer.tiles.size());
                for (AnimationProject.RasterTile tile : layer.tiles.values()) {
                    output.writeInt(tile.x);
                    output.writeInt(tile.y);
                    output.writeInt(AnimationProject.TILE_BYTES);
                    tileBuffer.clear();
                    tile.bitmap.copyPixelsToBuffer(tileBuffer);
                    output.write(tileBuffer.array());
                }
            }
        }
        output.flush();
    }

    private static AtomicFile recoveryFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "animink_plugin_recovery.aink"));
    }

    private static int checked(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("Invalid project size");
        return value;
    }

    private static int checkedRange(int value, int min, int max) throws IOException {
        if (value < min || value > max) throw new IOException("Invalid project dimensions");
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
