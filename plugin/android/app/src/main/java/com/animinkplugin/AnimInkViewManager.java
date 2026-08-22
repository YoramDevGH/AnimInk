package com.animinkplugin;

import androidx.annotation.NonNull;

import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;

/** Exposes the native AnimInk workspace to the React Native plugin container. */
public final class AnimInkViewManager extends SimpleViewManager<AnimInkPluginView> {
    static final String REACT_CLASS = "AnimInkNativeView";

    @NonNull
    @Override public String getName() {
        return REACT_CLASS;
    }

    @NonNull
    @Override protected AnimInkPluginView createViewInstance(@NonNull ThemedReactContext context) {
        return new AnimInkPluginView(context);
    }

    @Override public void onDropViewInstance(@NonNull AnimInkPluginView view) {
        view.destroyPluginView();
        super.onDropViewInstance(view);
    }
}
