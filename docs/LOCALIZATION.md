# Spanish and English

Select **Español / English** at sign-in or under **Settings → App language**. Spanish remains the initial language for existing and clean installations. Android 13+ also exposes supported languages in its per-app language settings. Older Android versions use the same in-app selector with a local preference.

Changing language recreates the activity. The selector is outside the active reader, so no WebView position is discarded. The preference survives process restarts and sign-out. It is not part of a reading backup and does not change the device's system language, a book's text, titles, user annotations, server responses or chosen TTS voice. Release notes supplied by a server retain their original language.

## Resources and code

- Spanish/default: `app/src/main/res/values/strings.xml`.
- English: `app/src/main/res/values-en/strings.xml`.
- Supported Android locales: `res/xml/locales_config.xml`.
- `AppLanguage.kt` applies device locale and resolves resources for Compose, workers, accessibility and notifications. Enum labels resolve on access, not once at process startup.
- `DefaultTexts.kt` mirrors the Spanish migrated strings for plain JVM model tests, which have no Android Resources. Keep this fallback aligned when modifying a resource. Android always uses Resources.
- Migrated `tr_*` resource IDs are stable. Do not renumber them when adding translations; new messages may use descriptive names. Preserve numbered placeholders exactly. Values passed as arguments are never interpreted as formatting instructions.
- Stored queue sentinels remain language-independent. `localizedStatus` translates known app-generated historical messages at presentation time, without mutating saved state. Unknown/server messages remain verbatim.
- Client-generated numeric volume/part labels can be localized; arbitrary server titles are not translated. Demo book text and downloaded content remain in their original language.

## Checks

Run `LanguageTest` for resource placeholders, enum refresh, preference persistence and legacy queue-state compatibility. `LanguageFlowTest` covers sign-in/settings selection, reopening, unchanged account state, English reader options and the e-ink profile. Also run the existing Spanish regressions when changing shared reader/download code. Check longer English labels with large fonts and in landscape; do not infer physical e-ink behaviour from emulator screenshots.
