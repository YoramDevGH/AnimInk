# AnimInk

AnimInk is a lightweight frame-by-frame drawing and animation app designed for the monochrome e-ink display of the **Supernote Nomad**. It runs fully offline, uses a landscape interface, and stores projects in the portable `.aink` format.

## Download

**[Download AnimInk 0.5.3 for Android](releases/AnimInk-0.5.3.apk?raw=1)**

SHA-256: [`d4d62f4238c2993ef2fa3b0156f9acf891bf670aecfe53e14e3b4edc98e2a9f3`](releases/AnimInk-0.5.3.apk.sha256)

The current APK is a preview build signed with the developer's Android debug key. Android 8.0 (API 26) or newer is required. It is intended primarily for Supernote Nomad; other Android or e-ink devices are not yet officially supported.

You can also find tagged versions on the [GitHub Releases page](../../releases).

### Install with ADB

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

### Requirements

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

## Project structure

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/illou/animink/` | Android application, canvas, project model, storage, and Supernote pen integration |
| `app/src/main/res/` | App resources, theme, launcher, and tool icons |
| `docs/` | Technical and e-ink performance research |
| `releases/` | Installable preview APKs published by the maintainer |
| `.github/workflows/` | Continuous integration and tagged GitHub Release automation |

## Fork and contribute

Use GitHub's **Fork** button, clone your fork, create a focused branch, and open a pull request. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change. Bug reports should include the device model, firmware version, reproduction steps, and—when relevant—a small non-sensitive `.aink` project.

## Privacy

AnimInk works offline and requests no network permission. Explicitly saved `.aink` files remain in the location selected by the user. Android app-data removal deletes only the private session-recovery copy, not separately exported project files.

## License

AnimInk is available under the [MIT License](LICENSE).
