# Releases, signing and forks

## Original channel

The updater is restricted to `https://claw.raishack.es/troop-reader/` and requires the same package/signature, an increasing version, matching size and SHA-256. Creating a GitHub Release **does not update the app's feed by itself**. See [APP-UPDATES](APP-UPDATES.md).

1. Increment versionCode/versionName. Preserve the signing certificate to support in-place updates.
2. Run tests/lint and check upgrades with completed/partial downloads, pending progress, bookmarks, settings and language preferences.
3. Publish immutable APKs, GPL source, release notes and hashes.
4. Publish `latest.json` last using `scripts/publish-update-feed.py`; it checks the HTTPS APK and signature against the previous release.
5. From the preceding version, verify discovery, download, Android installation confirmation and preserved data after reopening.

No private signing key is included. Existing alphas are development builds; preserving their certificate is the maintainer's responsibility. Do not promise that locally built debug APKs can update the distributed app.

## Independent forks

Use your own applicationId/update channel and stable release signing outside the repository. Review `UpdatePolicy.FEED`, host/path validation in `UpdateFeed.kt`, tests and `BASE` in the publishing script. Changing a URL alone is insufficient; fork users should not be directed to the original channel. The app must reject differently signed packages.

CI debug artifacts are for development only. They do not contain the maintainer's signing keys. Never replace an already announced APK; publish a correction with a higher versionCode.
