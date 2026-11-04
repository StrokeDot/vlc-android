# VLC Plus — In-app update and signing setup

This is an **unofficial** VLC for Android fork. Its update checker never uses VideoLAN nightly servers and does not accept XPlayer binaries.

## What users see

Open **VLC Plus → Settings → Advanced → Check for VLC Plus updates**.

When a newer signed VLC Plus release is published on `StrokeDot/vlc-android`, the app shows a confirmation, downloads the APK, verifies its published SHA-256 checksum, checks the Android package ID and version, and opens Android's normal installation confirmation screen. You may have to enable **Allow from this source** for VLC Plus once. Android never performs a silent install.

Updates are designed to retain app data and settings **after the first release is installed with the permanent signing key**.

## Important: migrating from the original BlueVLC debug APK

The first BlueVLC debug APK installed on November 4, 2026 was signed by a temporary GitHub Actions runner-generated debug key. A later debug APK was confirmed to use a *different* signer, so no system-supported method can update the first APK in place using the new APK. In addition, a signed release is packaged as `com.strokedot.bluevlc` (without the `.debug` suffix), whereas debug APKs are `com.strokedot.bluevlc.debug`.

Install the first permanently signed **VLC Plus release once**. You can then uninstall the old BlueVLC debug test app, leaving the original VLC app untouched. From that point onward, signed VLC Plus updates using the same package ID, signing key, and increasing versionCode can be installed over the existing VLC Plus installation without resetting settings.

## Set up private signing secrets (repository owner only)

Keep a private, backed-up JKS/PKCS12 keystore. **Do not commit** the keystore, base64 representation, passwords or backup files into the public repository.

In GitHub open **StrokeDot/vlc-android → Settings → Secrets and variables → Actions → New repository secret**, and create:

- `VLCPLUS_KEYSTORE_BASE64`: a one-line base64 encoding of the keystore bytes, without newlines.
- `VLCPLUS_SIGNING_PASSWORD`: the signing keystore/key password (same password for both in this project's generated signing backup).
- `VLCPLUS_SIGNING_ALIAS`: the private key alias (`vlcplus`).

The workflow `.github/workflows/vlcplus-signed-release.yml` will refuse to build unless all three secrets exist. It uses `assembleSignedRelease`, checks the APK signing certificate, writes an APK SHA-256 file, and publishes both as a normal GitHub Release. The private key is never included in the published assets.

For each new release, increment the root Gradle `versionCode`; the GitHub release tag must be exactly `vlcplus-<root versionCode>`. For the first updater feature branch, that is `vlcplus-3070201`. Once the workflow is present on the repository's default branch, run **VLC Plus signed release** from GitHub Actions.

## Constraints and security

- GitHub Releases must be public for the app to check/download them without GitHub authentication.
- This fork uses only signed release APK assets named `VLC-Plus-*-arm64-v8a.apk` (or another supported Android ABI / universal APK) and a matching `.apk.sha256` asset.
- The updater rejects unmatched APK package IDs, non-increasing Android APK versionCodes, and mismatched checksums; Android also verifies the app's signing identity.
- An app cannot update itself silently on an ordinary non-root Android phone. Android asks the user to confirm each APK install.
- No secrets are stored in the public source tree. Losing the permanent signing key means the package can no longer receive updates under that signing identity; retain an offline backup.
- The in-app updater and release workflow require a successful Android compile and runtime testing on a Xiaomi 15T before being considered production-ready.

Original VLC/Videolan licensing remains in `COPYING` and project source headers.
