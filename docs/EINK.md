# E-ink screens

## Enabling a profile

Choose **Settings → Screen type**, also available in Reading options and before sign-in:

- **Standard screen:** normal app behaviour.
- **Monochrome e-ink:** black/white UI and greyscale images. Photographs retain shades rather than being thresholded into binary black/white.
- **Colour e-ink:** black text and dark accents on white; content retains its colours. States also use labels, check marks and borders, never colour alone.

This is a **device preference**, not an account/series preference. Reading backups do not transfer it. Upgrades preserve your selection and do not enable flashing on a normal phone by default.

## Reading and UI behaviour

- Page curl and continuous vertical image reading are temporarily suspended. Continuous mode is displayed as Full page; saved preferences are restored when returning to Standard screen.
- Zoom, reading direction, two-page spreads, pinch, edge taps and optional volume-button controls remain available.
- In EPUB, forward/back first moves by one screen of text with 10% overlap and no animation. Only at the section boundary does it change section. Back from the start opens the end of the previous section. Contents still jump to specific sections/paragraphs.
- No spinning indicators or press ripples. Static switches/selections have 48 dp targets and explicit marks. Opaque full-screen menus/dialogs have no slide-in transition or translucent dimming background.
- Reading text is at least 18 CSS px; theme labels are at least 14 sp and respect Android font scaling. Links are underlined; annotations remain distinguishable by underline/border in monochrome. Downloaded originals are never modified.
- App lists have no inertial scrolling. Panning enlarged images or text triggers refresh after the gesture, not continuously during interaction.
- Reader controls hide only after another tap or navigation, not on a timer.
- Android's screen timeout is respected; the reader does not keep the screen on indefinitely in this mode.
- EPUB positions save on scroll completion, exit or pause; no periodic paragraph polling while the e-ink reader is idle.

## Refresh limitations

**Redrawing an Android view does not mean commanding a full physical panel refresh.** Waveforms and timings belong to the controller/firmware. Android has no universal public e-ink refresh command.

With **Refresh after navigation** enabled (the default inside e-ink profiles):

1. A refresh is requested after navigation, page readiness, menu opening/closing, foregrounding or gesture completion. Cover loads are coalesced to avoid one flash per image. A stable draw is awaited (approximately 180–220 ms), not every frame.
2. **Compatible BOOX:** tries `View.repaintEverything(int)` with full GC16 mode, reading constants from the firmware (`android.onyx.ViewUpdateHelper`). No guessed constants or A2/DU/fast modes. Manufacturer and API availability are checked. No root, global settings changes or Android restriction bypasses. Access failure falls back to compatibility mode.
3. **Bigme, experimental and opt-in:** detects the public `xrz.framework.manager.XrzEinkManager.forceGlobalRefresh(int)` extension and `EinkRefreshMode.EINK_CLEAN_MODE`. Reads the mode from firmware; detection does not request refreshes. A compatible method/field makes **Experimental Bigme refresh** available, initially disabled. Once enabled, global cleaning is requested after navigation. A failure persistently disables it and uses compatibility redraw; a changed firmware identity requires opting in again. No root or global mode changes. Evidence comes from another Bigme: **not physically validated on B751C S / Android 14 / 1.7.0**. See [Bigme details](BIGME-B751C-S.md).
4. **Other devices/API absent or disabled:** redraws the entire app window with black, white, then content (120 ms per phase in monochrome; 180 ms in colour). This is a compatibility aid, **not a guarantee of physical refresh or ghosting removal**. Zoom/progress are unchanged. It does not control the keyboard, Android dialogs or other apps.

**Refresh screen now** uses the same mechanism. Cleaning can be disabled while retaining the theme. The intentional flash and delay are distinct from the accidental black-page bug fixed in alpha12. Refreshes do not repeat while idle or backgrounded; they are cancelled on pause, mode change or window closure.

An accepted firmware call does not confirm the actual waveform. Emulators verify UI, requests and logic, **not physical refresh or ghosting**. The target Bigme B751C, S Color, Android 14, system 1.7.0 still needs physical verification. No certified hardware compatibility is claimed for BOOX, Bigme, Meebook, PocketBook or other manufacturers.

## Device configuration

- For stable text/comics, use **Normal/Quality** and full refresh on each page change if offered. A2/Fast/X favour motion but sacrifice detail and may leave more ghosting.
- Review manufacturer per-app optimisation: it can override refresh policy, enhance black, filter colours or force animations. Avoid aggressive double-refreshing by both system and app if too slow.
- Colour resolution, filters, front light and contrast vary. Do not apply binary thresholds to maps/illustrations or automatic saturation filters. The colour theme does not calibrate the panel.
- Correct refresh timing depends on controller, panel technology, temperature and firmware. Compatibility timings are defaults, not electrical panel parameters.
- Only devices running **Android 8+ apps** can install this APK. Standard Kindle/Kobo operating systems cannot.
- No changes to front light, colour temperature, global brightness, system permissions or device battery management.

## Research references (2026-09-19)

- [BOOX e-ink development guide](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Eink-Develop-Guide.md): contrast, transparency, animation, pagination and controls.
- [BOOX screen update](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Screen-Update.md): partial GU/REGAL and full GC.
- [BOOX update modes](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Update-Mode.md): speed/detail/ghosting trade-offs.
- [BOOX EpdController](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EpdController.md).
- API signatures/constants were checked against the official `onyxsdk-device:1.1.11` obtained over HTTPS from repo.boox.com. Neither that binary nor its old dependencies are bundled; the small independent adapter fails safely when unavailable.
- [Community xrz/Bigme research, pinned revision](https://github.com/imedwei/inksdk/blob/3373aa07506c0870ea229b9008cbb5fdbe8d706b/docs/bigme-sdk-reverse-engineered.md): public signatures/modes observed on HiBreak Plus, not B751C S certification. Its SDK is not imported.
