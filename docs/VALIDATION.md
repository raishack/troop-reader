# Validation and remaining limitations

## Alpha22 — bilingual interface

The language selector is available at sign-in and in Settings. Dedicated Android tests cover switching in both directions, reopening the app, preserving account state and English reader/e-ink controls. Unit tests verify resource placeholders, dynamic labels and compatibility with historical queue markers.

Final release results are recorded in the release notes after validation. A successful emulator run is not evidence of physical e-ink refresh quality.

## Previous releases

Archived alpha21 results: **181 unit tests and 9 distinct targeted Android tests**, with no lint errors. Alpha20's complete Android suite was not repeated for alpha21. Repeated runs are not counted as new coverage. The alpha20-to-alpha21 upgrade preserved 25 fixture files and the e-ink profile, with experimental Bigme refresh initially disabled.

The initial public checkout was built independently, with 181 unit tests, lint and the debug build passing. Main application sources matched alpha21. The locally generated updater fixture was checked against the local app certificate. Gitleaks found no secrets.

## Reproducing checks

See [BUILD](BUILD.md). Generate the updater fixture with your own development certificate and use a disposable emulator with an offline TTS voice. Never run destructive fixture tests on a personal device. Historical results do not imply the suite has run on every machine or firmware.

The Kavita 0.9.1.4 rehearsal is described in [KAVITA-UPGRADE](KAVITA-UPGRADE.md). It validates API and connector compatibility, not a complete Android test run.

## Still requires physical-device validation

- Bigme ghosting, latency and colour/monochrome contrast.
- Large libraries, battery consumption and prolonged background downloads.
- User acceptance of recently added reader features.

Do not promise complete ghosting removal or immunity to concurrent server updates.
