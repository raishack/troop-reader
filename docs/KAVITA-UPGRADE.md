# Controlled Kavita upgrades and integrations

Based on the validated **0.9.0.2 → 0.9.1.4 migration (2026-09-19)**. No private installation paths, accounts or backups are included. This is a procedure to adapt, not a script to run blindly.

## 1. Inventory and rollback plan

- Record versions, binaries/hashes, OS/architecture, service identity, configuration/database/media locations, proxy and persistent storage.
- Identify the Yamtrack connector, timer, configuration, matching state and database. Record allowed versions and healthy status before making changes.
- Download the exact official package and verify its published hash. Read migration requirements and release notes, including possible session invalidation.
- Define deployment failure criteria (integrity, authorization, media, progress or sync) and how to restore **previous binaries, configuration and database together**. Do not downgrade only the executable on a migrated database.

## 2. Backups and isolated rehearsal

- Create a consistent SQLite backup using the backup API or a stopped service. Do not copy only an active `.db` while ignoring WAL/SHM.
- For the final cutover, pause the connector and wait for any running job; stop Kavita before the final backup. Include data-protection keys and configuration in the private backup, never in Git.
- Keep the entire previous installation, service registration and startup dependencies. Back up connector database/configuration/state. Verify `PRAGMA integrity_check` and appropriate hashes.
- Rehearse with separate copies of database, configuration and media on a non-public port. Disable scheduled tasks/notifications and deny write access to originals. Synthetic test writes are allowed in this copy: **it must not become the production database**.
- Migrate the copy; compare users/libraries and progress/bookmark fingerprints before test writes.

## 3. Acceptance matrix

| Area | Check |
|---|---|
| Service/proxy | Stable startup, health, verified HTTPS, external URL and automatic startup |
| Authentication | Session renewal, account permissions, anonymous images denied |
| Media | Series/volume/file covers; first/middle/last manga/PDF pages |
| EPUB | First/middle/last sections, contents, actual images, CSS and fonts |
| Progress | Write/read manga and EPUB positions **only in the isolated copy** |
| Bookmarks | Create/read/delete page bookmarks and EPUB personal contents **only in the copy** |
| Yamtrack | Allowed version, authentication, history and full connector dry-run |

If an adjustment is needed, rebuild it against the candidate binary and retest authorization. Never reuse patched DLLs from older versions. Keep original/patched hashes and the exact adjustment source.

## 4. Cutover and verification

- Pause sync and take the final consistent backup. Preserve the previous version and deploy the validated package with **final production data**, never rehearsal data.
- Preserve service/proxy identity and paths. Start/migrate, verify integrity and reading fingerprints.
- Repeat read-only checks through actual HTTPS; localhost tests alone do not validate the public proxy. Do not write synthetic progress in production.
- Add only the explicitly tested connector version to its allowlist. For bind-mounted configuration files, preserve the inode or recreate the mount deliberately: renaming the host file may leave a container reading the old one.
- Run a dry-run, then an authorized real connector cycle. Confirm counts, no errors and an active timer. Do not change matching rules to hide version failures.
- Disable any automatic rollback watchdog only after acceptance. Stop the rehearsal, close its temporary port/firewall rule, and document backups, results, limitations and recovery instructions.

## 5. Rollback

On failure: pause sync, stop the service, save the new database/configuration to recover recent reading changes, restore the **complete consistent previous installation**, restore connector configuration/state as needed, verify service/API/sync and reactivate the timer. A late rollback may lose changes made after the backup; do not run it blindly.

## Historical result and limits

The documented migration preserved progress/bookmark fingerprints; both databases passed integrity checks. Authenticated API and HTTPS proxy checks passed. Yamtrack dry-run and real cycle: 3 unchanged items, 0 errors, timer reactivated. Test writes occurred only in the copy. Pages/sections were sampled, not the whole library or a new physical mobile-device run. Troop Reader remained on alpha21.
