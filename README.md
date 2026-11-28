# toidicakhia's patches

A collection of patches for Android apps, built for [Morphe](https://morphe.software).

<a href="https://morphe.software/add-source?github=toidicakhia/morphe-app-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## What's inside

This bundle currently supports the following apps:

| # | App | Patches | Version | Package |
|---:|-----|:------:|---------|---------|
| 1 | [**1.1.1.1**](PATCHES.md#1111-comcloudflareonedotonedotonedotone) | 3 | `6.38.11` | [`com.cloudflare.onedotonedotonedotone`](https://play.google.com/store/apps/details?id=com.cloudflare.onedotonedotonedotone) |
| 2 | [**EZVIZ**](PATCHES.md#ezviz-comezviz) | 3 | `7.6.1.0824` | [`com.ezviz`](https://play.google.com/store/apps/details?id=com.ezviz) |
| 3 | [**HTTP Sniffer**](PATCHES.md#http-sniffer-comanetcapturemock) | 1 | `2.5.2-ad_mob`, `2.4.9-ad_mob` | [`com.anetcapture.mock`](https://play.google.com/store/apps/details?id=com.anetcapture.mock) |
| 4 | [**Locket Widget**](PATCHES.md#locket-widget-comlocketlocket) | 4 | `1.247.3` | [`com.locket.Locket`](https://play.google.com/store/apps/details?id=com.locket.Locket) |
| 5 | [**NetMonster**](PATCHES.md#netmonster-czmroczisnetmonster) | 1 | `4.0.5` | [`cz.mroczis.netmonster`](https://play.google.com/store/apps/details?id=cz.mroczis.netmonster) |
| 6 | [**Proxyman**](PATCHES.md#proxyman-comproxymanproxymanandroid) | 1 | `1.33.0` | [`com.proxyman.proxymanandroid`](https://play.google.com/store/apps/details?id=com.proxyman.proxymanandroid) |
| 7 | [**TeraBox**](PATCHES.md#terabox-comduboxdrive) | 1 | `4.29.0` | [`com.dubox.drive`](https://play.google.com/store/apps/details?id=com.dubox.drive) |
| 8 | [**Universal Copy**](PATCHES.md#universal-copy-comcamelcorpuniversalcopy) | 1 | `7.2.0` | [`com.camel.corp.universalcopy`](https://play.google.com/store/apps/details?id=com.camel.corp.universalcopy) |
| 9 | [**VTVGo**](PATCHES.md#vtvgo-vnvtvvtvgo) | 7 | `12.11.19` | [`vn.vtv.vtvgo`](https://play.google.com/store/apps/details?id=vn.vtv.vtvgo) |
| 10 | [**WeDJ**](PATCHES.md#wedj-compioneerdjwedj) | 2 | `1.1.4.5`, `1.1.1.3` | [`com.pioneerdj.WeDJ`](https://play.google.com/store/apps/details?id=com.pioneerdj.WeDJ) |

Only the exact versions listed above are supported. Newer app releases usually need
updated fingerprints before a patch can be applied. Patch descriptions, options, and
supported versions are listed in [PATCHES.md](PATCHES.md).

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
