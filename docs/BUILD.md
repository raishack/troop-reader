# Building and testing

## Requirements

JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Platform Tools, and access to Google Maven, Maven Central and Gradle. The wrapper uses Gradle 8.11.1, AGP 8.9.1 and Kotlin 2.1.10. Use an Android Studio version compatible with AGP 8.9.1.

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK, or create an untracked `local.properties` containing `sdk.dir=/path/to/sdk` (use `/` on Windows). Neither SDKs nor keys are distributed.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Use `gradlew.bat` on Windows. Output: `app/build/outputs/apk/debug/app-debug.apk`. This is a development build: its local debug signature **is not necessarily the maintainer's signature** and cannot update an installation signed with another key. Configure your own stable release signing for production; never commit keys.

## Instrumented tests

Only use a disposable emulator/device without personal accounts or downloads. API 35 is the reference environment. Install an offline TTS voice for audio tests and grant requested Android permissions. Tests use fixtures and MockWebServer, not private libraries. Some change orientation, font scale, animations and audio: do not interact with the emulator during execution. Legacy regression tests use Spanish; language tests restore that setting afterwards.

The updater checks the fixture APK's signature. Generate it using the same local debug key as this machine's app, **never distribute the publisher's key**:

```sh
python3 scripts/prepare-update-fixture.py
./gradlew connectedDebugAndroidTest
```

The generator creates a synthetic code-free APK with the app's package and version 100000, under `androidTest/assets`. Never install it on a personal device. It is excluded from the main APK and Git.

Reports: `app/build/reports/tests`, `app/build/reports/lint-results-debug.html` and `app/build/outputs/androidTest-results`. Documentation-only changes need link checks; download/sync/reader changes require relevant regression tests and a data-preserving upgrade check.
