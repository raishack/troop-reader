# In-app update protocol

Since alpha15 the app checks `https://claw.raishack.es/troop-reader/latest.json`. This origin is separate from Kavita and never receives its credentials.

## Device behaviour

- Check on opening/resuming, at most once every six hours; immediate manual checks under Settings → App updates.
- WorkManager checks approximately every twelve hours. Battery restrictions, connectivity and force-stop can delay it.
- Automatic downloads are initially enabled on unmetered networks (normally Wi-Fi). Turning them off does not disable detection. Manual downloads ask for confirmation of the current connection, including mobile data.
- A toolbar indicator and notification are shown when permitted. No automatic dialog covers the reader or launches installation in the background.
- Android requires authorizing Troop Reader as an installation source once and confirming each update. No silent installation or root is used.
- Interrupted APK downloads restart; partial packages are never installed. Reading downloads use a separate directory and are untouched.
- Size, SHA-256, package, version, minimum Android and matching certificate are checked before marking the APK ready and again before opening the installer.
- A private FileProvider shares only update APKs with temporary read access, not account data.

## Publishing

1. Increase versionCode/versionName in `app/build.gradle.kts`; retain the package ID and signing key.
2. Build/test and prepare the APK, GPL source, notes and verification report.
3. Publish the APK with a new immutable filename under `/troop-reader/`.
4. Publish **latest.json last**, using `scripts/publish-update-feed.py`. It extracts version/minimum Android from the APK, checks signing and ascending version, computes size/hash, verifies HTTPS download and atomically replaces the feed.
5. Keep previous APKs. Never announce a version before its APK is available, reuse versionCode or replace published bytes.

Schema 1: `schemaVersion`, `packageName`, `versionCode`, `versionName`, `minSdk`, `apkUrl`, `sizeBytes`, `sha256`, `notes`. Feed limit: 32 KiB; APK limit: 200 MiB. The client rejects HTTP, other hosts/ports, URL query parameters and redirects. Normal TLS certificate validation is mandatory.

Alpha14 and earlier require one manual installation of alpha15 or later. Never uninstall or clear data to update.

## Tests

`ReaderTestRunner` disables automatic update checks during instrumentation; tests never contact the production feed. They use MockWebServer and a tiny APK generated locally with the same debug signature, only under `androidTest/assets`.

The fixture is not committed or included in the main APK. Run `python3 scripts/prepare-update-fixture.py` before instrumented tests. It uses the same applicationId, versionCode 100000, versionName `0.1.0-fixture100000`, minSdk 26 and an application with `android:hasCode="false"`. Use only a disposable emulator. See [BUILD](BUILD.md).
