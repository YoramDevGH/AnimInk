# Release process

This file is for AnimInk maintainers.

## Prepare a preview release

1. Update `versionCode` and `versionName` in `app/build.gradle`.
2. Run `./gradlew :app:lintDebug :app:assembleDebug`.
3. Verify the APK on a real Supernote Nomad.
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to `releases/AnimInk-<version>.apk`.
5. Update the version and download link in `README.md`.
6. Verify the signing certificate with `apksigner verify --print-certs releases/AnimInk-<version>.apk` and keep the same signing key across updates.
7. Commit the source and APK, then create and push a matching tag:

   ```bash
   git tag v<version>
   git push origin main v<version>
   ```

The `publish-release.yml` workflow checks that the matching APK exists, creates its SHA-256 checksum, and publishes both files as a GitHub Release.

## Production signing

The current downloadable APK is a preview signed with the maintainer's Android debug key. Before wider production distribution, configure a dedicated release keystore outside the repository, back it up securely, and move signing credentials to GitHub Actions secrets. Never commit a private signing key or its passwords.
