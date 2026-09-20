# Architecture and contracts

Source: `app/src/main/java/es/gamingtroop/reader`.

| Area | Files |
|---|---|
| UI/entry | MainActivity, UiSupport, Models |
| Language | AppLanguage, DefaultTexts, Android strings resources |
| API/catalogue | Api, Catalog, Repository, Covers, Discovery |
| Persistence/sync | Storage, BookmarkSync |
| Downloads/local HTML | Workers, OfflineHtml |
| Reader | ReaderScreen, ReaderGestures, PageTurnPolicy, PageCurl, ContinuousReader |
| Navigation/offline | ReaderNavigator, ReadingJourney, OfflineLibrary, OfflineIndex |
| EPUB/speech | EpubTools, EpubToolsUi, BookSpeaker, VoicePlayback |
| Personal features | PersonalLibrary, PersonalUi, ReadingBackup, ServerShelves |
| Updates | AppUpdates, UpdateFeed, UpdateUi |
| E-ink | Eink, DisplayUi, BigmeRefresh |
| Diagnostics/demo | Diagnostics, Demo |

## Invariants

- State is isolated by server/account; sessions use Android Keystore. Never persist passwords or log tokens.
- Use `AtomicFile`; do not rewrite identical state. Removing downloads must keep pending progress/bookmarks.
- Cancellation is real: obsolete workers cannot revive the queue or overwrite newer intent.
- Offline HTML has no external access or native JavaScript bridge; preserve sanitization and resource path limits.
- Keep the previous image until the next is ready; preserve zoom and page-curl framing. Edge taps are immediate; double-tap zoom is central.
- Partial reading does not skip missing pages or complete unfinished downloads. Audio and reader UI do not compete for progress.
- Conflicts are manual by default. Kavita does not guarantee remote compare-and-swap.
- E-ink temporarily overrides normal preferences without deleting them. Refresh only after stable content; never loop while idle or backgrounded.
- Language is a device preference. It must not translate identifiers, protocol fields, account data, titles or user content. Persistent queue markers remain stable across languages.

Personal features (favourites, statistics, notes, per-series settings) are not automatically synchronized to Kavita. Server collections are read-only. Reading backups exclude book files, credentials and device-specific native-refresh activation.
