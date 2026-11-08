# Validation and remaining limitations

## Alpha23 — public Bigme integration (2026-11-08)

The public branch ports the native settings/cleaning integration from the local Spanish alpha22 device build while preserving bilingual resources and the existing released-reader callback guards. Version code 23 avoids distributing different bytes under the already installed local version code 22. Release assets and the app update feed are published separately from source commits.

Local checks on the integrated public source passed **192 unit tests**, lint without errors, `assembleDebug` and `assembleDebugAndroidTest`. Resource/placeholder and enum-label tests cover Spanish and English. Targeted emulator coverage includes Bigme routing/failure/consent, explicit native-panel action, English native controls, e-ink behaviour and language switching; the targeted run finished with **16 Android tests passed** (6 Bigme, 6 e-ink, 4 language), for **208 distinct automated checks** including unit tests. It ran only on a disposable emulator.

Physical evidence belongs to the equivalent local Spanish alpha22 integration: an ordinary targetSdk-35 probe confirmed the exported settings-provider query; the native panel was inspected for Troop Reader with HD 256 shown, and the owner confirmed that manual native cleaning returned to content. The bilingual alpha23 source has **not** been installed on that physical reader by this repository update. Colour/ghosting/latency improvements have not been quantified. No instrumented fixture tests ran on personal data.

Upgrade checks on the disposable API-35 emulator passed from both the public alpha21 APK and the local Spanish alpha22 APK to the signed bilingual alpha23 candidate. Both retained 26 account fixture files, including reading payloads and a partial-download file, queued downloads, pending progress, bookmarks, reading settings, the colour e-ink profile and an English language preference; the reopened interface remained English. Certificates matched across all three APKs. No personal device was used for these fixture checks.

The app uses the public provider only after checking its system owner and typed capability reply. It does not bypass the reflection restriction or redistribute extracted system components. See [Bigme details](BIGME-B751C-S.md).

## Alpha22 — bilingual interface

The language selector is available at sign-in and in Settings. Dedicated Android tests cover switching in both directions, reopening the app, preserving account state and English reader/e-ink controls. Unit tests verify resource placeholders, dynamic labels and compatibility with historical queue markers.

Final release results are recorded in the release notes after validation. A successful emulator run is not evidence of physical e-ink refresh quality.

## Previous releases

Archived alpha21 results: **181 unit tests and 9 distinct targeted Android tests**, with no lint errors. Alpha20's complete Android suite was not repeated for alpha21. Repeated runs are not counted as new coverage. The alpha20-to-alpha21 upgrade preserved 25 fixture files and the e-ink profile, with experimental Bigme refresh initially disabled.

The initial public checkout was built independently, with 181 unit tests, lint and the debug build passing. Main application sources matched alpha21. The locally generated updater fixture was checked against the local app certificate. Gitleaks found no secrets.

## Reproducing checks

See [BUILD](BUILD.md). Generate the updater fixture with your own development certificate and use a disposable emulator with an offline TTS voice. Never run destructive fixture tests on a personal device. Historical results do not imply the suite has run on every machine or firmware.

The Kavita 0.9.1.4 rehearsal is described in [KAVITA-UPGRADE](KAVITA-UPGRADE.md). It validates API and connector compatibility, not a complete Android test run.

## Still requires broader physical-device validation

- Bigme ghosting, latency and colour/monochrome contrast.
- Large libraries, battery consumption and prolonged background downloads.
- User acceptance of recently added reader features.

Do not promise complete ghosting removal or immunity to concurrent server updates.
