# AnimInk

AnimInk is a lightweight frame-by-frame drawing and animation tool designed for monochrome Supernote e-ink displays. It runs fully offline, uses a landscape interface, and stores projects in the portable `.aink` format.

## Download

Choose either edition. They coexist in this repository and use the same `.aink` project format.

| Edition | Download | Installation |
| --- | --- | --- |
| Supernote Plugin | **[AnimInk 0.5.3 `.snplg`](releases/AnimInk-0.5.3.snplg?raw=1)** | Install from Supernote's Plugins settings |
| Android App | **[AnimInk 0.5.3 `.apk`](releases/AnimInk-0.5.3.apk?raw=1)** | Sideload as a standalone Android application |

Plugin SHA-256: [`720aabba0f46fb39d21712c93bb23896ad2a25fce90c38abe9eccc7377c69bcf`](releases/AnimInk-0.5.3.snplg.sha256)

APK SHA-256: [`d4d62f4238c2993ef2fa3b0156f9acf891bf670aecfe53e14e3b4edc98e2a9f3`](releases/AnimInk-0.5.3.apk.sha256)

The plugin is the recommended edition on firmware that provides **Settings → Apps → Plugins**. The APK remains available for devices or firmware without plugin support. The APK is a preview build signed with the developer's Android debug key and requires Android 8.0 (API 26) or newer.

You can also find tagged versions on the [GitHub Releases page](../../releases).

### Install the Supernote Plugin

1. Copy `AnimInk-0.5.3.snplg` to the device's `MyStyle` directory.
2. Open **Settings → Apps → Plugins**.
3. Select **Add Plugin**, choose the package, and install it.
4. Open NOTE or DOC and select **AnimInk** from the plugin toolbar.

### Install the Android App with ADB

1. Enable Android debugging on the device and connect it by USB.
2. Download the APK, then run:

```bash
adb install -r AnimInk-0.5.3.apk
```

If Android rejects an update because it was signed with a different key, uninstall the existing preview build first. Uninstalling deletes the app's private recovery copy, so export important `.aink` projects before doing so.

## Features

- Pressure-sensitive stylus drawing.
- Two independent raster layers per frame: gray **Sketch** and black **Final**.
- Pencil, ink pen, and lasso eraser tools.
- Two-finger tap to undo and three-finger tap to redo.
- Frame creation, deletion, timeline navigation, and sequential playback.
- Local New, Open, and Save actions for `.aink` project files.
- Session recovery when the app sleeps or closes.
- Automatic migration of legacy vector v1 and raster v2 projects.
- No network permission, background service, sensor use, or permanent wake lock.

AnimInk uses Supernote's native pen path when available for low-latency strokes and falls back to standard Android drawing elsewhere. More details are available in [the performance notes](docs/PERFORMANCE_RESEARCH.md).

## Build from source

### Android App requirements

- JDK 17
- Android SDK Platform 35
- Git

The Gradle wrapper is included, so a separate Gradle installation is not required.

```bash
git clone https://github.com/<your-github-username>/AnimInk.git
cd AnimInk
./gradlew :app:assembleDebug
```

On Windows PowerShell, use `./gradlew.bat :app:assembleDebug` instead. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

To run the same checks as GitHub Actions:

```bash
./gradlew :app:lintDebug :app:assembleDebug
```

### Supernote Plugin requirements

- Node.js 18 or newer
- JDK 19 or newer, as recommended by the Supernote SDK documentation
- Android SDK Platform 35 and Build-Tools 35.0.0

```bash
cd plugin
npm ci
npm run typecheck
npm run lint
./buildPlugin.sh
```

On Windows PowerShell, run `./buildPlugin.ps1`. The package is generated at `plugin/build/outputs/animink_plugin.snplg`.

## Project structure

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/illou/animink/` | Android application, canvas, project model, storage, and Supernote pen integration |
| `app/src/main/res/` | App resources, theme, launcher, and tool icons |
| `plugin/` | Supernote React Native plugin, native Android view bridge, and packaging scripts |
| `docs/` | Technical and e-ink performance research |
| `releases/` | Installable `.apk` and `.snplg` packages published by the maintainer |
| `.github/workflows/` | Continuous integration and tagged GitHub Release automation |

## Fork and contribute

Use GitHub's **Fork** button, clone your fork, create a focused branch, and open a pull request. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change. Bug reports should include the device model, firmware version, reproduction steps, and—when relevant—a small non-sensitive `.aink` project.

## Privacy

AnimInk works offline and requests no network permission. Explicitly saved `.aink` files remain in the location selected by the user. Android app-data removal deletes only the private session-recovery copy, not separately exported project files.

## License

AnimInk is available under the [MIT License](LICENSE).
