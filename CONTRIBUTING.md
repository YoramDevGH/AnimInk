# Contributing to AnimInk

Thank you for helping improve AnimInk.

## Before you start

- Search existing issues before opening a new one.
- Keep pull requests focused on one change.
- Do not include private `.aink` projects, device identifiers, signing keys, `local.properties`, or generated `build/` files.
- Do not replace APK or SNPLG files in `releases/`; those are maintained as part of the release process.

## Development workflow

1. Fork the repository and clone your fork.
2. Create a branch such as `feature/onion-skinning` or `fix/frame-navigation`.
3. Make the change and update documentation when behavior changes.
4. Run the project checks:

   ```bash
   ./gradlew :app:lintDebug :app:assembleDebug
   ```

   For plugin changes, also run:

   ```bash
   cd plugin
   npm ci
   npm run typecheck
   npm run lint
   ./buildPlugin.sh
   ```

5. Test on a Supernote Nomad when the change affects pen input, refresh behavior, gestures, file access, or playback.
6. Open a pull request that explains the change, how it was tested, and any e-ink performance impact.

## Bug reports

Please include:

- AnimInk version;
- device model and firmware version;
- exact reproduction steps;
- expected and actual behavior;
- screenshots or a small non-sensitive project when useful.

Never publish documents or projects that contain personal information.
