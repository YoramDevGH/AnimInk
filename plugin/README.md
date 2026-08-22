# AnimInk Supernote Plugin

This directory contains the Supernote Plugin edition of AnimInk. The standalone Android application remains in the repository root; both editions use the same `.aink` project format.

The plugin follows Supernote's official React Native 0.79.2 plugin template and packages AnimInk's Android drawing engine as a native `ReactPackage`. This preserves the pressure-sensitive canvas, two raster layers, timeline, local recovery, and low-latency Supernote pen integration.

## User interface

All user-facing plugin text is in English:

- File, New, Open, and Save;
- Pencil, Ink, and Eraser;
- Sketch and Final layers;
- frame controls, playback status, confirmation dialogs, and accessibility labels.

## Install

1. Download `AnimInk-0.5.3.snplg` from the repository's `releases/` directory or GitHub Releases.
2. Copy it to the `MyStyle` directory on the Supernote device.
3. Open **Settings → Apps → Plugins → Add Plugin**.
4. Select the package and install it.
5. Open NOTE or DOC and select **AnimInk** from the plugin toolbar.

The Plugins settings entry is available only on Supernote firmware with plugin support.

## Build

Requirements:

- Node.js 18 or newer;
- JDK 19 or newer, following the official Supernote environment guide;
- Android SDK Platform 35 and Build-Tools 35.0.0.

Install dependencies and run the checks:

```bash
npm ci
npm run typecheck
npm run lint
```

Package on Linux or macOS:

```bash
./buildPlugin.sh
```

Package on Windows PowerShell:

```powershell
.\buildPlugin.ps1
```

The package is written to `build/outputs/animink_plugin.snplg`.

## Structure

| Path | Purpose |
| --- | --- |
| `index.js` | Initializes `PluginManager` and registers the AnimInk NOTE/DOC toolbar button |
| `App.tsx` | React Native PluginHost surface and close action |
| `android/app/src/main/java/com/animinkplugin/` | Native canvas, project model, storage, pen engine, view manager, and React package |
| `PluginConfig.json` | Stable plugin identity and package metadata |
| `assets/icon.png` | Monochrome plugin icon |
| `buildPlugin.ps1`, `buildPlugin.sh` | Official Supernote packaging scripts |

## Privacy

The plugin has no Internet permission and performs no network requests. Projects are opened and saved through Android's document picker. A namespaced private recovery file is maintained inside the plugin host so it cannot collide with other plugins.

## Compatibility

The plugin is optimized for landscape Supernote e-ink devices. Runtime verification on a physical device is still required because PluginHost and native pen behavior cannot be fully reproduced by a desktop Android build.

The project is licensed under the repository's [MIT License](../LICENSE).
