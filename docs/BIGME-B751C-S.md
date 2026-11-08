# Bigme B751C S: native settings and cleaning

## Scope and observed behaviour

The target was identified commercially as B751C / S Color, Android 14, with system 1.7.0 on the earlier About screen. The inspected Android build reports Bigme / HiBreak / mt6765, a 1264×1680 screen and build `Bigme_V1.020260518`. Internal names alone do not identify the panel or prove API compatibility. No serial number is needed or retained by the app.

On 8 November 2026, system apps/framework were inspected locally. An ordinary permission-free probe targeting API 35 could load xrz classes but not reflect their control members or `View/Window/SurfaceView.setRefreshMode`: Android reported hidden-API access denied. Public Java visibility and success as shell UID do **not** prove access from a normal app.

The exported settings provider could be queried by that same ordinary app. The resulting integration was installed in place as a local Spanish alpha22 build. The manufacturer's panel opened for **Troop Reader**, showing **HD 256**, and the owner confirmed manual native cleaning returned correctly to the content. This is a functional check on one device, **not** a measurement of colour accuracy, ghosting or latency.

The bilingual repository integrates these changes as **alpha23 development source**, preserving its translations and reader lifecycle fixes. This source update does not publish a new APK or update feed.

## Using the integration

1. Choose **Settings → Screen type → Colour e-ink** (or Monochrome e-ink on an appropriate device).
2. **Bigme quality and colour** explicitly opens the manufacturer's e-ink centre for the foreground app. Select the profile there manually; changes persist in the system, not in a reading backup. Opening the panel itself enables manufacturer optimisation flags. Scaling/content-brightness controls can also be offered. The app never opens it during detection or chooses profiles automatically per book.
3. For cleaning, enable **Refresh after navigation** and **Native Bigme cleaning**, then try **Refresh screen now**. Disable cleaning if the intentional flash is unwanted.
4. **Compatibility and refresh** shows status and submitted-request count. Diagnostic exports add only route/availability enums, booleans and counts, not model/build identifiers, book names or accounts.

Manufacturer labels can disagree with IDs/enum names: in the tested firmware `rb_magazine` was labelled “Comic” and showed HD 256, while `rb_comic` was labelled “Magazine”. Do not infer the visible profile/waveform from an enum name or assume the same labels on other firmware.

## Public-provider contract

The independent adapter requires the enabled/exported `com.xrz.settings.provider.SettingProvider`, owned by the **system app** `com.xrz.settings`, at authority `com.xrz.SettingProvider`. A similarly named third-party provider is rejected. Package visibility is declared; no new permission, root, shell helper, SDK binary or hidden-API bypass is added.

Only three fixed operations are allowed:

| Operation | Purpose | When called |
|---|---|---|
| `isColorDevice` | Typed capability query; false is valid for monochrome | Detection only |
| `setting_globalrefresh` | Manufacturer's native cleaning command | Opt-in navigation/manual cleaning |
| `setting_einkcenter` | Native e-ink panel for the foreground app | Explicit button press only |

The inspected provider returns `status=-1` even when executing an operation. A non-null reply is **submission**, not proof of a physical waveform, visible settings window or ghosting removal. Missing/malformed capability replies do not enable the route. Catchable cleaning failures disable it persistently and restore compatibility redraw without retry loops.

Pending clean requests are cancelled/coalesced and suppressed while opening the native panel. Own-window focus/input resumes normal operation. There is no idle/background refresh loop. A changed firmware or access route requires new cleaning consent; reading/profile preferences are retained.

## Framework fallback and limits

Other firmware can still use the alpha21-style `XrzEinkManager.forceGlobalRefresh(int)` adapter when its runtime symbolic `EINK_CLEAN_MODE` is accessible. Detection never refreshes and no borrowed numeric waveform is guessed. When neither route works, compatibility redraw remains.

The internal framework has representation/per-package policies, but the app does **not** bypass Android restrictions to change contrast, colour or quality automatically per EPUB/manga page. Manual control is delegated to the native panel. It does not automatically change brightness, DPI, waveform files or device power management.

No manufacturer APKs/JARs/libraries, captures of personal reading content, signing keys or private operational history are distributed here.

References: [manufacturer specification](https://store.bigme.vip/products/bigme-b751c-s-upgraded-7inch-color-ereder-with-android-14-os), [community xrz research](https://github.com/imedwei/inksdk/blob/33c71c9578007db3f2b3ee4767e63472b9802600/docs/bigme-sdk-reverse-engineered.md), [e-ink behaviour](EINK.md). Community research is not an official SDK and does not substitute for target-firmware tests.
