# Example screenshots

These unedited screenshots were captured from Troop Reader **0.1.0-alpha22** in an Android emulator with the English interface and the built-in offline demo. They illustrate the development version on `main`, not a new APK release.

| Screenshot | What it shows |
| --- | --- |
| [Offline library](library.png) | Synthetic covers, continue-reading cards, offline availability and library filters. |
| [Reading options](reading-options.png) | Screen types, page layouts, continuous reading and navigation choices. |
| [E-ink settings](eink-settings.png) | High-contrast controls, interface language and refresh settings. |

The books, text, covers and account are synthetic fixtures provided by [`Demo.kt`](../../app/src/main/java/es/gamingtroop/reader/Demo.kt). No personal reading library, real account credentials or private server details appear in these images. The `demo.invalid` address is a non-operational example.

Book titles and demo content remain in Spanish even when the interface is in English. Emulator screenshots show the e-ink interface styling only; they do not demonstrate physical panel refresh or ghosting performance.

To explore the same demo, build a debug APK using the [build guide](../BUILD.md), select **English** on the sign-in screen, and choose **Try the demo without an account**. Use the library, a demo title's reading options and **Settings** to explore the screens shown above. No Kavita server is needed.

Demo fixtures and screenshots are distributed under the project's [GPL-3.0 license](../../LICENSE).
