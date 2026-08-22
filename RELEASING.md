# Release process

This file is for AnimInk maintainers.

## Prepare a preview release

1. Update `versionCode` and `versionName` in `app/build.gradle`.
2. Run `./gradlew :app:lintDebug :app:assembleDebug`.
3. Verify the APK on a real Supernote Nomad.
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to `releases/AnimInk-<version>.apk`.
5. Build the Supernote edition with `plugin/buildPlugin.ps1` or `plugin/buildPlugin.sh`.
6. Copy `plugin/build/outputs/animink_plugin.snplg` to `releases/AnimInk-<version>.snplg`.
7. Update both version fields and download links in `README.md` and `plugin/PluginConfig.json`.
8. Verify the APK signing certificate with `apksigner verify --print-certs releases/AnimInk-<version>.apk` and keep the same signing key across updates.
9. Commit the source and both packages, then create and push a matching tag:

   ```bash
   git tag v<version>
   git push origin main v<version>
   ```

The `publish-release.yml` workflow checks that both matching editions exist, creates their SHA-256 checksums, and publishes the four files as a GitHub Release.

## Production signing

The current downloadable APK is a preview signed with the maintainer's Android debug key. Before wider production distribution, configure a dedicated release keystore outside the repository, back it up securely, and move signing credentials to GitHub Actions secrets. Never commit a private signing key or its passwords.
