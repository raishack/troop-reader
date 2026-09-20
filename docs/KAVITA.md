# Setting up Kavita

## Normal installation

1. Install an official [Kavita release](https://github.com/Kareadita/Kavita/releases) and create libraries using content you are entitled to use.
2. Expose it over HTTPS with a valid certificate. The reverse proxy must preserve authentication headers, paths and binary responses. Do not disable TLS verification.
3. Create a regular user with library access and **Download** permission. Troop Reader does not need administrator privileges.
4. Sign in using the HTTPS URL. Test covers, a manga download, an EPUB with images/fonts, offline reading and sync using test data.

The app uses header-based sessions and session renewal. You do not need to enter API keys or place them in URLs. A Kavita server and Yamtrack connector are not bundled.

## Verified compatibility

| Server | Findings |
|---|---|
| 0.9.0.2 | The tested installation required making `apiKey` optional on five image routes. Historical tool in `server-compat/0.9.0.2`. |
| 0.9.1.4 | Cover routes are fixed upstream. The tested installation still required the adjustment to `ReaderController.GetImage(apiKey)`, one parameter only. |
| Other versions | Not certified: test before upgrading or modifying. Never reuse an older patched DLL. |

If an authenticated request to `api/Reader/image` returns HTTP 400 stating that `apiKey` is required, see [server-compat](../server-compat/README.md). **This is not a mandatory patch for every Kavita installation:** first confirm the version, response and cause. HTTP 401/403, missing permissions or missing files are different problems.

The adjustment changes parameter nullability metadata; it does not remove authentication or change controller instructions. Test it in an isolated copy: anonymous requests must remain denied. Do not use a dummy `apiKey` or globally disable authorization.

## EPUB and conflicts

Since alpha18 the client resolves certain alternative EPUB font paths within the same book. If a download fails, keep its queue entry and select Retry; the item details show the error. Do not erase reading progress to fix HTTP 400.

Conflicts are manual by default. **Give this device priority** only sends pending local changes. Restored backups must be compared with the server, never blindly applied over newer data.

## Yamtrack

The integration is independent of the app. The tested installation uses [kavita-yamtrack-sync](https://github.com/raishack/kavita-yamtrack-sync) with an explicit `allowed_kavita_versions` list. This is a connector setting, **not** a standard setting to add to any Yamtrack installation. See [controlled upgrades](KAVITA-UPGRADE.md).
