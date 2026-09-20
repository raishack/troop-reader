# Bigme B751C · S Color · Android 14 · system 1.7.0

## Device identification

The reported About screen identifies **B751C**, **S Color**, Android **14**, system **1.7.0**. This corresponds to the manufacturer's [B751C S](https://store.bigme.vip/products/bigme-b751c-s-upgraded-7inch-color-ereder-with-android-14-os), correcting the initial B7 reference. No serial number is retained.

The official specification lists a 7-inch panel, 300 ppi monochrome and 150 ppi colour. **Colour e-ink** is the recommended profile; no saturation boost, binary threshold or fast mode is forced. Black text, strong lines and labelled states are preserved in both profiles.

## Conditional firmware support since alpha21 — not certified

- Checks the public **xrz** framework, rather than assuming the product name/Android version is sufficient. It does not require Build.MANUFACTURER to say Bigme; some models report alps.
- Required contract: public `xrz.framework.manager.XrzEinkManager`, public static `void forceGlobalRefresh(int)`, and public static final integer `EINK_CLEAN_MODE` in `xrz.framework.manager.EinkRefreshMode`.
- No guessed numeric modes, borrowed GC16 constants when CLEAN is missing, private API bypasses, root/shell commands, extra permissions or persistent system mode changes.
- **Experimental Bigme refresh** appears only when the contract is found. It is initially disabled. Enabling it permits clean requests after navigation; detection itself never calls forceGlobalRefresh.
- A catchable failure persistently disables it and falls back to compatibility redraw, without retry loops. Java cannot catch a fatal crash inside manufacturer native code, which is why brand detection never enables it automatically.
- The option is device-local and excluded from reading backups. A changed firmware/API identity invalidates activation. No re-login is needed; books, zoom and progress are unaffected.
- **Compatibility and refresh** displays detection status. **My space → Diagnostics** exports status, profile, activation and accepted-request count, not model, serial, build identifier or account.

## Checking on the device

1. Update in place to alpha21 or later. Select **Settings → Screen type → Colour e-ink** and **Refresh after navigation**.
2. If **Experimental Bigme refresh** appears, enable it and try **Refresh screen now**. Compare text/illustrated pages and open/close menus. Disable it if it does not improve results or behaves incorrectly.
3. If unavailable, open **Compatibility and refresh** to see whether the API is missing/blocked. No native refresh is claimed in that case; compatibility redraw remains. Diagnostics can be exported without personal data.
4. Review Bigme's own per-app quality/full-refresh settings if available. Do not combine fast mode with expectations of a perfectly clean image. Exact firmware 1.7.0 menu labels have not been verified.

## Evidence and limits

API source: [community HiBreak Plus research, pinned revision](https://github.com/imedwei/inksdk/blob/3373aa07506c0870ea229b9008cbb5fdbe8d706b/docs/bigme-sdk-reverse-engineered.md). This is not an official SDK or a B751C S test. Working under shell UID does not prove a normal app has permission. The app checks the contract and handles rejections within its own process, without bypassing controls.

Automated tests use a simulated controller and an emulator without the Bigme API. They verify default exclusion, symbolic invocation, fallback, UI and data preservation, **not physical cleaning or absence of ghosting**. No actual B751C S panel result has been recorded. An accepted void call does not reveal the waveform used.
