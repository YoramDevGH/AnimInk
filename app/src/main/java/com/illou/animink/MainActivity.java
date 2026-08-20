package com.illou.animink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements InkCanvasView.Listener {
    private static final int REQUEST_OPEN = 20;
    private static final int REQUEST_CREATE = 21;
    private static final long EINK_FRAME_SETTLE_MS = 400L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> frameButtons = new ArrayList<>();
    private AnimationProject project;
    private InkCanvasView canvas;
    private LinearLayout timeline;
    private TextView status;
    private ImageButton pencilButton;
    private ImageButton inkButton;
    private ImageButton eraserButton;
    private ImageButton playButton;
    private Button sketchLayerButton;
    private Button sketchVisibilityButton;
    private Button finalLayerButton;
    private Button finalVisibilityButton;
    private boolean playing;
    private int playbackFrame;
    private Uri currentUri;
    private String projectName;
    private boolean dirty;
    private SharedPreferences session;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        session = getSharedPreferences("session", MODE_PRIVATE);
        project = ProjectStore.loadRecovery(this);
        projectName = session.getString("name", "Sans titre");
        dirty = session.getBoolean("dirty", false);
        String savedUri = session.getString("uri", null);
        if (savedUri != null && !savedUri.isEmpty()) currentUri = Uri.parse(savedUri);

        buildInterface();
        selectTool(InkCanvasView.TOOL_PENCIL);
        refreshTimeline();
        refreshStatus();
        // Also completes migration from the version 0.1 autosave when present.
        saveRecoveryNow();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = row(Color.WHITE);
        Button fileButton = textButton("Fichier", v -> showFileMenu(v));
        GradientDrawable fileBackground = new GradientDrawable();
        fileBackground.setColor(Color.rgb(205, 205, 205));
        fileBackground.setCornerRadius(dp(12));
        fileButton.setBackground(fileBackground);
        fileButton.setTextSize(16f);
        header.addView(fileButton, new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.BLACK);
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(18), 0, dp(12), 0);
        header.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView gestureHint = new TextView(this);
        gestureHint.setText(R.string.frame_swipe_hint);
        gestureHint.setTextColor(Color.DKGRAY);
        gestureHint.setTextSize(12f);
        gestureHint.setGravity(Gravity.CENTER);
        header.addView(gestureHint, new LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout workspace = row(Color.WHITE);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tools.setPadding(dp(6), dp(8), dp(6), dp(8));
        tools.setBackgroundColor(Color.rgb(55, 55, 55));

        pencilButton = iconButton(R.drawable.ic_pencil, "Crayon", v -> selectTool(InkCanvasView.TOOL_PENCIL));
        inkButton = iconButton(R.drawable.ic_ink, "Encre", v -> selectTool(InkCanvasView.TOOL_INK));
        eraserButton = iconButton(R.drawable.ic_eraser, "Gomme", v -> selectTool(InkCanvasView.TOOL_ERASER));
        ImageButton deleteButton = iconButton(R.drawable.ic_trash, "Supprimer l’image", v -> deleteFrame());
        tools.addView(pencilButton, toolParams());
        tools.addView(inkButton, toolParams());
        tools.addView(eraserButton, toolParams());
        tools.addView(deleteButton, toolParams());

        LinearLayout sketchRow = layerRow("Croquis", AnimationProject.LAYER_SKETCH);
        sketchLayerButton = (Button) sketchRow.getChildAt(0);
        sketchVisibilityButton = (Button) sketchRow.getChildAt(1);
        tools.addView(sketchRow, layerParams());

        LinearLayout finalRow = layerRow("Final", AnimationProject.LAYER_FINAL);
        finalLayerButton = (Button) finalRow.getChildAt(0);
        finalVisibilityButton = (Button) finalRow.getChildAt(1);
        tools.addView(finalRow, layerParams());
        workspace.addView(tools, new LinearLayout.LayoutParams(dp(126), ViewGroup.LayoutParams.MATCH_PARENT));

        canvas = new InkCanvasView(this);
        canvas.setProject(project);
        canvas.setListener(this);
        refreshLayerControls();
        workspace.addView(canvas, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(workspace, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout transport = row(Color.rgb(52, 52, 52));
        playButton = iconButton(R.drawable.ic_play, "Lire", v -> togglePlayback());
        applyIconStyle(playButton, false, true);
        transport.addView(playButton, new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT));

        HorizontalScrollView timelineScroll = new HorizontalScrollView(this);
        timelineScroll.setFillViewport(true);
        timelineScroll.setHorizontalScrollBarEnabled(false);
        timeline = row(Color.rgb(52, 52, 52));
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        timeline.setPadding(dp(8), dp(8), dp(8), dp(8));
        timelineScroll.addView(timeline, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        transport.addView(timelineScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(transport, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        setContentView(root);
    }

    private LinearLayout row(int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(color);
        return row;
    }

    private Button textButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setOnClickListener(listener);
        return button;
    }

    private ImageButton iconButton(int icon, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setOnClickListener(listener);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        applyIconStyle(button, false, false);
        return button;
    }

    private LinearLayout.LayoutParams toolParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(62), dp(62));
        params.bottomMargin = dp(8);
        return params;
    }

    private LinearLayout layerRow(String label, int layer) {
        LinearLayout row = row(Color.TRANSPARENT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button select = textButton(label, v -> selectLayer(layer));
        Button visibility = textButton("●", v -> toggleLayer(layer));
        visibility.setTextSize(18f);
        visibility.setContentDescription("Afficher ou cacher le calque " + label);
        row.addView(select, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(visibility, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private LinearLayout.LayoutParams layerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        params.bottomMargin = dp(6);
        return params;
    }

    private void applyIconStyle(ImageButton button, boolean selected, boolean darkBar) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(selected ? Color.BLACK : Color.WHITE);
        background.setStroke(dp(2), darkBar ? Color.WHITE : Color.DKGRAY);
        button.setBackground(background);
        button.setImageTintList(ColorStateList.valueOf(selected ? Color.WHITE : Color.BLACK));
    }

    private void applyFrameStyle(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? Color.rgb(40, 40, 40) : Color.rgb(205, 205, 205));
        background.setStroke(dp(1), Color.BLACK);
        button.setBackground(background);
        button.setTextColor(selected ? Color.WHITE : Color.BLACK);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void selectTool(int tool) {
        canvas.setTool(tool);
        applyIconStyle(pencilButton, tool == InkCanvasView.TOOL_PENCIL, false);
        applyIconStyle(inkButton, tool == InkCanvasView.TOOL_INK, false);
        applyIconStyle(eraserButton, tool == InkCanvasView.TOOL_ERASER, false);
    }

    private void selectLayer(int layer) {
        if (playing) stopPlayback();
        canvas.setActiveLayer(layer);
        refreshLayerControls();
        refreshStatus();
    }

    private void toggleLayer(int layer) {
        if (playing) stopPlayback();
        canvas.setLayerVisible(layer, !canvas.isLayerVisible(layer));
        markDirty();
        refreshLayerControls();
    }

    private void refreshLayerControls() {
        if (canvas == null || sketchLayerButton == null) return;
        applyLayerStyle(sketchLayerButton,
                canvas.getActiveLayer() == AnimationProject.LAYER_SKETCH);
        applyLayerStyle(finalLayerButton,
                canvas.getActiveLayer() == AnimationProject.LAYER_FINAL);
        applyVisibilityStyle(sketchVisibilityButton,
                canvas.isLayerVisible(AnimationProject.LAYER_SKETCH));
        applyVisibilityStyle(finalVisibilityButton,
                canvas.isLayerVisible(AnimationProject.LAYER_FINAL));
    }

    private void applyLayerStyle(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? Color.BLACK : Color.rgb(210, 210, 210));
        background.setStroke(dp(1), Color.WHITE);
        button.setBackground(background);
        button.setTextColor(selected ? Color.WHITE : Color.BLACK);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void applyVisibilityStyle(Button button, boolean visible) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), visible ? Color.BLACK : Color.GRAY);
        button.setBackground(background);
        button.setText(visible ? "●" : "○");
        button.setTextColor(visible ? Color.BLACK : Color.GRAY);
    }

    private void showFileMenu(View anchor) {
        if (playing) stopPlayback();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Nouveau");
        menu.getMenu().add(0, 2, 1, "Ouvrir…");
        menu.getMenu().add(0, 3, 2, "Sauvegarder");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) confirmDiscardThen(this::newProject);
            else if (item.getItemId() == 2) confirmDiscardThen(this::requestOpen);
            else if (item.getItemId() == 3) requestSave();
            return true;
        });
        menu.show();
    }

    private void confirmDiscardThen(Runnable action) {
        if (!dirty) {
            action.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Modifications non sauvegardées")
                .setMessage("Continuer sans sauvegarder le fichier ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Continuer", (dialog, which) -> action.run())
                .show();
    }

    private void newProject() {
        project = new AnimationProject();
        currentUri = null;
        projectName = "Sans titre";
        dirty = false;
        canvas.setProject(project);
        refreshLayerControls();
        refreshTimeline();
        refreshStatus();
        persistSession();
        saveRecoveryNow();
    }

    private void requestOpen() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN);
    }

    private void requestSave() {
        if (currentUri != null) {
            saveToUri(currentUri);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, projectName.endsWith(".aink") ? projectName : projectName + ".aink");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CREATE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        takePersistablePermission(uri, data.getFlags());
        if (requestCode == REQUEST_OPEN) openFromUri(uri);
        else if (requestCode == REQUEST_CREATE) {
            currentUri = uri;
            projectName = displayName(uri);
            saveToUri(uri);
        }
    }

    private void takePersistablePermission(Uri uri, int flags) {
        int allowed = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, allowed); }
        catch (SecurityException ignored) { }
    }

    private void openFromUri(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Fichier inaccessible");
            project = ProjectStore.load(input);
            currentUri = uri;
            projectName = displayName(uri);
            dirty = false;
            canvas.setProject(project);
            refreshLayerControls();
            refreshTimeline();
            refreshStatus();
            persistSession();
            saveRecoveryNow();
            Toast.makeText(this, "Projet ouvert", Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            Toast.makeText(this, "Impossible d’ouvrir ce projet", Toast.LENGTH_LONG).show();
        }
    }

    private void saveToUri(Uri uri) {
        canvas.commitCurrentFrame();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Fichier inaccessible");
            ProjectStore.save(output, project);
            currentUri = uri;
            projectName = displayName(uri);
            dirty = false;
            persistSession();
            saveRecoveryNow();
            refreshStatus();
            Toast.makeText(this, "Projet sauvegardé", Toast.LENGTH_SHORT).show();
        } catch (IOException error) {
            Toast.makeText(this, "Sauvegarde impossible", Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null ? "Projet.aink" : segment;
    }

    private void addBlankFrame() {
        if (playing) stopPlayback();
        int insertAt = canvas.getFrameIndex() + 1;
        project.frames.add(insertAt, new AnimationProject.DrawingFrame());
        canvas.setFrameIndex(insertAt);
        markDirty();
        refreshTimeline();
    }

    private void deleteFrame() {
        if (playing) stopPlayback();
        if (project.frames.size() == 1) {
            project.frames.get(0).clear();
            canvas.rebuildCurrentFrame();
        } else {
            int oldIndex = canvas.getFrameIndex();
            project.frames.remove(oldIndex).clear();
            canvas.setFrameIndex(Math.min(oldIndex, project.frames.size() - 1));
        }
        markDirty();
        refreshTimeline();
    }

    private void selectFrame(int index) {
        if (playing) stopPlayback();
        canvas.setFrameIndex(index);
        refreshTimelineSelection();
        refreshStatus();
    }

    @Override public void onFrameSwipe(int direction) {
        selectFrame(canvas.getFrameIndex() + direction);
    }

    @Override public void onStrokeFinished() { markDirty(); }

    private void togglePlayback() {
        if (playing) stopPlayback(); else startPlayback();
    }

    private void startPlayback() {
        if (project.frames.size() < 2) return;
        playing = true;
        playbackFrame = canvas.getFrameIndex();
        canvas.setPlaying(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playButton.setImageResource(R.drawable.ic_stop);
        playButton.setContentDescription("Arrêter");
        applyIconStyle(playButton, true, true);
        canvas.presentPlaybackFrame(playbackFrame);
    }

    private void stopPlayback() {
        playing = false;
        handler.removeCallbacks(playbackAdvance);
        canvas.setPlaying(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playButton.setImageResource(R.drawable.ic_play);
        playButton.setContentDescription("Lire");
        applyIconStyle(playButton, false, true);
        refreshTimelineSelection();
        refreshStatus();
    }

    @Override public void onPlaybackFramePresented() {
        if (!playing) return;
        refreshTimelineSelection();
        refreshStatus();
        playbackFrame = (canvas.getFrameIndex() + 1) % project.frames.size();
        handler.postDelayed(playbackAdvance, EINK_FRAME_SETTLE_MS);
    }

    private final Runnable playbackAdvance = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            canvas.presentPlaybackFrame(playbackFrame);
        }
    };

    private void refreshTimeline() {
        timeline.removeAllViews();
        frameButtons.clear();
        for (int i = 0; i < project.frames.size(); i++) {
            final int index = i;
            Button frameButton = textButton(Integer.toString(i + 1), v -> selectFrame(index));
            frameButtons.add(frameButton);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.MATCH_PARENT);
            params.rightMargin = dp(4);
            timeline.addView(frameButton, params);
        }
        ImageButton addButton = iconButton(R.drawable.ic_add, "Ajouter une image", v -> addBlankFrame());
        applyIconStyle(addButton, false, true);
        timeline.addView(addButton, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.MATCH_PARENT));
        refreshTimelineSelection();
        refreshStatus();
    }

    private void refreshTimelineSelection() {
        int selected = canvas.getFrameIndex();
        for (int i = 0; i < frameButtons.size(); i++) applyFrameStyle(frameButtons.get(i), i == selected);
    }

    private void refreshStatus() {
        String marker = dirty ? " • modifié" : "";
        String layer = canvas.getActiveLayer() == AnimationProject.LAYER_SKETCH ? "Croquis" : "Final";
        String hidden = canvas.isLayerVisible(canvas.getActiveLayer()) ? "" : " (caché)";
        status.setText(String.format(Locale.getDefault(), "%s%s    Image %d / %d    %s%s",
                projectName, marker, canvas.getFrameIndex() + 1, project.frames.size(), layer, hidden));
    }

    private void markDirty() {
        dirty = true;
        persistSession();
        refreshStatus();
    }

    private void saveRecoveryNow() {
        if (canvas != null) canvas.commitCurrentFrame();
        try { ProjectStore.saveRecovery(this, project); }
        catch (IOException ignored) { }
    }

    private void persistSession() {
        SharedPreferences.Editor editor = session.edit()
                .putString("name", projectName)
                .putBoolean("dirty", dirty);
        if (currentUri == null) editor.remove("uri");
        else editor.putString("uri", currentUri.toString());
        editor.apply();
    }

    @Override protected void onPause() {
        super.onPause();
        if (playing) stopPlayback();
        saveRecoveryNow();
        canvas.setActive(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (canvas != null) canvas.setActive(true);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
