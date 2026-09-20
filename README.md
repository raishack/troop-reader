# Troop Reader

An independent **Kavita** client for Android, built for offline EPUB, manga, comics and PDF reading. **Spanish and English interface**, Kotlin and Jetpack Compose. Android 8.0+ (API 26), GPL-3.0. This is an alpha application, not an official Kavita app.

## Install and connect

1. Download the APK from [Releases](https://github.com/raishack/troop-reader/releases) or the [maintainer's update channel](https://claw.raishack.es/troop-reader/).
2. Install over the previous version **without uninstalling or clearing app data**. Android asks you to confirm installation.
3. Select **Español / English** on the sign-in screen or under **Settings → App language**. Existing installations start in Spanish. This changes the interface, not your books, annotations or reading voice.
4. Enter **your** Kavita HTTPS URL and an account with library access and the **Download** permission. The form initially suggests the project's server; replace it with yours. No shared accounts or server access are included.
5. Download a book/volume or select **Try the demo without an account**. Check offline reading, resume and synchronization.
6. Read the [Kavita setup guide](docs/KAVITA.md): some versions need a server compatibility adjustment for header-authenticated images.

Future versions from the original channel are detected under **Settings → App updates**. Android still requires confirmation; updates are never installed silently.

## Features

- Online/offline libraries grouped by series, cached covers, batch downloads and a pause/resume queue.
- Read while downloading, without skipping missing content.
- Instant edge taps by default, optional swipes, pinch and central double-tap zoom; retained zoom, RTL, two-page spreads and optional page curl.
- Continuous vertical reading, volume/page selection, thumbnails, EPUB contents and direct page/section navigation.
- Progress and bookmark sync with manual conflict resolution by default; optional device priority.
- Favourites, personal collections, followed series, optional next-volume downloads and per-series reading preferences.
- EPUB: local search, exportable highlights/notes, dictionary through a compatible app and background offline text-to-speech.
- Optional volume-button navigation, orientation lock, statistics, reading backups and diagnostic exports.
- Read-only Kavita lists/collections with offline caching.
- [Monochrome and colour e-ink modes](docs/EINK.md), including [experimental Bigme refresh](docs/BIGME-B751C-S.md).

## Documentation

- [Build and test](docs/BUILD.md)
- [Kavita setup and compatibility](docs/KAVITA.md)
- [Controlled Kavita upgrades, Yamtrack and rollback](docs/KAVITA-UPGRADE.md)
- [Architecture and data contracts](docs/ARCHITECTURE.md)
- [Languages and translation maintenance](docs/LOCALIZATION.md)
- [Releases, signing and forks](docs/RELEASING.md)
- [In-app update protocol](docs/APP-UPDATES.md)
- [Validation and limitations](docs/VALIDATION.md)
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Licensing](NOTICE.md)

## Important limitations

Kavita does not provide conditional writes for all reading data. Conflict detection reduces, but cannot eliminate, races between clients. Deleting downloaded files preserves progress and bookmarks. Reading backups **do not contain books or passwords**.

Speech needs an installed offline engine/voice. Dictionary lookup needs an app supporting Android's Process text action. There is no OCR, manga/PDF text search, remote list editing or complete SSO/2FA support.

An emulator cannot validate ghosting on a physical e-ink panel. Bigme refresh is opt-in and remains physically unverified on B751C S / Android 14 / firmware 1.7.0. A black/white redraw does not guarantee a full panel refresh.

## Quick development setup

JDK 17, Android SDK 35 and Build Tools 35.0.0. Configure `ANDROID_HOME` or an untracked `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Never run instrumented tests on a device containing real reading data. See [BUILD](docs/BUILD.md) first. CI artifacts use a development signature and cannot necessarily update a maintainer-signed installation.
