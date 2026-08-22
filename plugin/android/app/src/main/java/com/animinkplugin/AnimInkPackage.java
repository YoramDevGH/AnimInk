package com.animinkplugin;

import androidx.annotation.NonNull;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.ViewManager;

import java.util.Collections;
import java.util.List;

/** React package loaded by the Supernote plugin host for AnimInk's native view. */
public final class AnimInkPackage implements ReactPackage {
    @NonNull
    @Override public List<NativeModule> createNativeModules(
            @NonNull ReactApplicationContext reactContext) {
        return Collections.emptyList();
    }

    @NonNull
    @SuppressWarnings("rawtypes")
    @Override public List<ViewManager> createViewManagers(
            @NonNull ReactApplicationContext reactContext) {
        return Collections.singletonList(new AnimInkViewManager());
    }
}
