# toidicakhia's patches

A collection of patches for Android apps, built for [Morphe](https://morphe.software).

<a href="https://morphe.software/add-source?github=toidicakhia/morphe-app-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## What's inside

This bundle currently supports the following apps:

| # | App | Patches | Version | Package |
|---:|-----|:------:|---------|---------|
| 1 | [**1.1.1.1**](https://play.google.com/store/apps/details?id=com.cloudflare.onedotonedotonedotone) | 3 | `6.38.11` | `com.cloudflare.onedotonedotonedotone` |
| 2 | [**EZVIZ**](https://play.google.com/store/apps/details?id=com.ezviz) | 3 | `7.6.1.0824` | `com.ezviz` |
| 3 | [**HTTP Sniffer**](https://play.google.com/store/apps/details?id=com.anetcapture.mock) | 1 | `2.5.2-ad_mob`, `2.4.9-ad_mob` | `com.anetcapture.mock` |
| 4 | [**Locket Widget**](https://play.google.com/store/apps/details?id=com.locket.Locket) | 3 | `1.247.3` | `com.locket.Locket` |
| 5 | [**NetMonster**](https://play.google.com/store/apps/details?id=cz.mroczis.netmonster) | 1 | `4.0.5` | `cz.mroczis.netmonster` |
| 6 | [**Proxyman**](https://play.google.com/store/apps/details?id=com.proxyman.proxymanandroid) | 1 | `1.33.0` | `com.proxyman.proxymanandroid` |
| 7 | [**TeraBox**](https://play.google.com/store/apps/details?id=com.dubox.drive) | 1 | `4.29.0` | `com.dubox.drive` |
| 8 | [**Universal Copy**](https://play.google.com/store/apps/details?id=com.camel.corp.universalcopy) | 1 | `7.2.0` | `com.camel.corp.universalcopy` |
| 9 | [**VTVGo**](https://play.google.com/store/apps/details?id=vn.vtv.vtvgo) | 7 | `12.11.19` | `vn.vtv.vtvgo` |
| 10 | [**WeDJ**](https://play.google.com/store/apps/details?id=com.pioneerdj.WeDJ) | 2 | `1.1.4.5`, `1.1.1.3` | `com.pioneerdj.WeDJ` |

Only the exact versions listed above are supported. Newer app releases usually need
updated fingerprints before a patch can be applied.

## Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.9.0](https://github.com/toidicakhia/morphe-app-patches/releases/tag/v1.9.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;23 patches total
<details open>
<summary><img src=".github/assets/apps/vn.vtv.vtvgo.png" alt="" height="20"> VTVGo&nbsp;&nbsp;•&nbsp;&nbsp;7 patches</summary>
<br>

**🎯 Supported versions:**

| 12.11.19 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Block Product Ad Banners](#block-product-ad-banners) | Points the product-ad API endpoint at a non-existent path so in-app ad banners are not loaded. Best effort: ads embedded in content responses are not affected. |  |
| [Disable Analytics / Telemetry](#disable-analytics-telemetry) | Disables Firebase Analytics, Crashlytics and Performance, stops Sentry uploads and turns off Facebook app events. The firebase_data_collection_default_enabled flag also prevents the SDKs from re-enabling collection. |  |
| [Remove Ad ID Permissions](#remove-ad-id-permissions) | Removes the advertising ID, Privacy Sandbox ad-services and Play install-referrer permissions. Device identification used for device management is left untouched. |  |
| [Remove Calendar Permissions](#remove-calendar-permissions) | Removes calendar read/write permissions. Programme reminders will no longer work. |  |
| [Remove Video Ads](#remove-video-ads) | Removes VTVGo pre-roll, mid-roll, welcome TVC and other VAST/IMA ads by rejecting ad requests before playback starts. |  |
| [Trust User Certificates](#trust-user-certificates) | Adds user-installed CA certificates to the network security config so native traffic can be inspected with a debugging proxy. Flutter's Dart HTTP stack keeps its own trust store. |  |
| [Video quality control](#video-quality-control) | Adds an on-screen settings menu for the player's video quality. Auto follows the app's own selection; the other presets override the player's quality caps. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.locket.Locket.png" alt="" height="20"> Locket Widget&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.247.3 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass SSL Pinning](#bypass-ssl-pinning) | For TLS debugging: trusts user-installed CA certificates, skips OkHttp certificate pinning and accepts any hostname, so a local proxy can inspect HTTPS traffic. Not selected by default. |  |
| [Spoof Firebase signing certificate](#spoof-firebase-signing-certificate) | Sends Locket's original signing-certificate SHA-1 to Firebase Installations, Remote Config, App Check and Auth. Required after repackaging: Morphe re-signs the APK, and Google otherwise rejects the requests with HTTP 403. | • Original Firebase SHA-1 |
| [Unlock Locket Gold](#unlock-locket-gold) | Makes the app treat the signed-in account as a Locket Gold subscriber, enabling the Gold-only feature gates and replacing the paywall with subscription management. Client-side only: features validated by Locket's servers still need a real subscription. | • Force feature gates |

</details>

<details open>
<summary><img src=".github/assets/apps/com.ezviz.png" alt="" height="20"> EZVIZ&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 7.6.1.0824 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass SSL pinning](#bypass-ssl-pinning) | Bypasses EZVIZ certificate pinning and hostname checks so a debugging proxy can read its HTTPS traffic. Also trusts user-installed certificates. Not selected by default. |  |
| [Bypass Signature Check](#bypass-signature-check) | Skips the repack signature hash check in LoadingActivity that closes the app before it can start. |  |
| [Remove Ads](#remove-ads) | Removes banner, splash and hot open-screen ads. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.dubox.drive.png" alt="" height="20"> TeraBox&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 4.29.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass SSL pinning](#bypass-ssl-pinning) | Skips TeraBox certificate pinning and hostname checks, trusts user-installed certificates and lets the WebView ignore certificate errors, so a proxy can read HTTPS. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.cloudflare.onedotonedotonedotone.png" alt="" height="20"> 1.1.1.1&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 6.38.11 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable Analytics / Telemetry](#disable-analytics-telemetry) | Disables all Cloudflare telemetry by no-oping the analytics bundle builder and the Firebase event dispatchers. |  |
| [Disable SSL Pinning](#disable-ssl-pinning) | Bypasses OkHttp certificate pinning on Cloudflare API calls to allow TLS traffic inspection. |  |
| [Spoof WARP+ Unlimited UI](#spoof-warp-unlimited-ui) | Forces WarpPlusState to UNLIMITED on every AccountData instance by intercepting the primary constructor before the account type field is written. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.pioneerdj.WeDJ.png" alt="" height="20"> WeDJ&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 1.1.4.5 | 1.1.1.3 |
| :---: | :---: |
| Latest release; requires Android 13+. | Use on Android 9-12. |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable Analytics / Telemetry](#disable-analytics-telemetry) | Stops WeDJ's Mobylog tracking client from uploading playback, device-connection and startup app-check events. |  |
| [Unlock Unlimited Playback](#unlock-unlimited-playback) | Makes WeDJ treat the "Unlock Unlimited Playback" in-app purchase as owned. The free-version limit that stops playback after loading a few tracks is removed and the purchase entry disappears from the track browser. Use WeDJ 1.1.1.3 on Android 9-12 and 1.1.4.5 on Android 13+. Client-side only: the Play Store transaction itself is not simulated. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.camel.corp.universalcopy.png" alt="" height="20"> Universal Copy&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 7.2.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Plus](#unlock-plus) | Unlocks Universal Copy Plus: removes ads, upgrade prompts and the Internet permission, and bypasses the PairIP license check so the patched app starts. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/cz.mroczis.netmonster.png" alt="" height="20"> NetMonster&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 4.0.5 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Premium](#unlock-premium) | Unlocks all premium features. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.proxyman.proxymanandroid.png" alt="" height="20"> Proxyman&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.33.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Pro](#unlock-pro) | Unlocks Pro: unlimited SSL proxying entries, filters, pinning, block/map/rewrite and breakpoint rules, the advanced body editor, Compose entries, Live Update and the widget. Also bypasses the PairIP license check so the repacked app starts. |  |

</details>

<details open>
<summary><img src=".github/assets/apps/com.anetcapture.mock.png" alt="" height="20"> HTTP Sniffer&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.5.2-ad_mob | 2.4.9-ad_mob |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock VIP](#unlock-vip) | Forces permanent professional VIP tier, removes ads and upgrade popups, bypasses PairIP. |  |

</details>

<!-- PATCHES_END -->

&nbsp;

## How to use

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) or
   [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).
2. Add this source with the link at the top of this page, or add the generated
   `patches-bundle.json` URL manually.
3. Select an app and the patches you want, then apply them.
4. Install the patched APK. Uninstall the original app first if the signatures conflict.

> **Note:** patched APKs are signed with a different key. Back up your app data before
> replacing the original app. Some patches intentionally remove permissions or features,
> such as the Internet permission in Universal Copy.

## Build from source

You need JDK 21, Android SDK 36 (`ANDROID_HOME` or `sdk.dir` in `local.properties`), and
GitHub credentials with `read:packages` for the Morphe GitHub Packages registry.

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<token with read:packages>
export SHARED_MAPS_API_KEY=<optional Google Maps key for NetMonster>

./gradlew :patches:buildAndroid
```

The bundle is written to `patches/build/libs/patches-*.mpp` and can be applied with
Morphe Desktop or the Morphe CLI. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full
setup, build, and development guide.

## Contributing

Contributions are welcome — new patches, updated fingerprints, bug fixes, and docs.
See [CONTRIBUTING.md](CONTRIBUTING.md) for prerequisites, the development workflow,
and tips for working with patches.

## License

toidicakhia's patches are licensed under the [GNU General Public License v3.0](LICENSE).
