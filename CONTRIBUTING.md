# Contributing to toidicakhia's patches

Thanks for your interest in improving this patch bundle! This guide covers the local
setup, build commands, project layout, and the workflow used for changes.

For an overview of the project and the list of supported apps, see the [README](README.md).

## Prerequisites

- JDK 21.
- Android SDK 36 (`ANDROID_HOME` or `sdk.dir` in `local.properties`).
- GitHub credentials with `read:packages` for the Morphe GitHub Packages registry.
  Set `GITHUB_ACTOR` / `GITHUB_TOKEN`, or add `gpr.user` / `gpr.key` to
  `~/.gradle/gradle.properties`.

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<token with read:packages>
export SHARED_MAPS_API_KEY=<optional Google Maps key for NetMonster>
```

## Building the patch bundle

```bash
./gradlew :patches:buildAndroid
```

The bundle is written to `patches/build/libs/patches-*.mpp`. Apply it with
Morphe Desktop, or with the Morphe CLI:

```bash
java -jar morphe-desktop.jar patch \
  -p patches/build/libs/patches-<version>.mpp \
  -e "Patch name" \
  app.apk
```

## Regenerating generated files

`patches-list.json`, `patches-bundle.json`, the README patch list, and `CHANGELOG.md` are
generated. Do not edit them by hand. To refresh the patch list and README table locally:

```bash
./gradlew :patches:generatePatchesList
python3 .github/scripts/generate_patches_readme.py \
  toidicakhia/morphe-app-patches main patches-list.json README.md
```

The script replaces everything between the `<!-- PATCHES_START EXPANDED -->` and
`<!-- PATCHES_END -->` markers in `README.md`, so keep those markers intact.

## Project layout

- `patches/src/main/kotlin/me/toidicakhia/patches/` — patch code, grouped by app.
- `patches/src/main/kotlin/me/toidicakhia/patches/shared/` — compatibility declarations
  and shared patch helpers.
- `extensions/extension/` — Java code injected into patched apps.
- `.github/scripts/` — helper script that regenerates the README patch list.
- `patches-list.json`, `patches-bundle.json`, `CHANGELOG.md` — generated metadata and
  release history. Do not edit them by hand.

## Development workflow

- Make changes on the `main` branch.
- Use [semantic commit](https://kapeli.com/cheat_sheets/Semantic_Commits.docset/Contents/Resources/Documents/index)
  messages:
  - `feat: add a new feature`
  - `fix: fix a problem`
  - `chore: a change that should not appear in the user-facing changelog`
- Build the bundle locally with `./gradlew :patches:buildAndroid`. No release automation
  is configured.
- Do not edit generated files by hand.

## Tips

- See the [patcher documentation](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/1_patcher_intro.md)
  for examples of patches and fingerprints.
- When adding support for a new app version, verify every fingerprint against the exact
  APK and update the compatibility target in `shared/Constants.kt`.

## License

By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0](LICENSE).
