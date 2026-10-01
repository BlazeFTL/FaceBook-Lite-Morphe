# morphe-fb-lite

[Morphe](https://github.com/MorpheApp) patches for **Facebook Lite** (`com.facebook.lite`).

Add these patches to Morphe: https://morphe.software/add-source?github=ToThangGTVT/morphe-fb-lite

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.0.1](https://github.com/ToThangGTVT/morphe-fb-lite/releases/tag/v1.0.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 Facebook Lite&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 530.0.0.8.106 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Use system font](#use-system-font) | Blocks the downloaded Meta fonts (Optimistic, Instagram Sans, emoji, ...) so the app falls back to the system font. |  |

</details>

<!-- PATCHES_END -->

Tested on Facebook Lite `530.0.0.8.106` (arm64-v8a, APKMirror APK).

## How "Use system font" works

Facebook Lite ships no fonts in the APK. At runtime it downloads them into
`/data/data/com.facebook.lite/files/` and loads them with `Typeface.createFromFile()`.
If loading fails, it falls back to `Typeface.create(name, 0)`, i.e. the system font.

The font loader lives in the secondary dex packed in
`assets/secondary-program-dex-jars/store-0.dex.spo` (Facebook Superpack format),
which the patcher cannot modify. So the patch hooks the primary dex instead:

1. `ClientApplicationSplittedShell.attachBaseContext()` calls
   `UseSystemFontPatch.blockDownloadedFonts(context)` at startup.
2. The extension replaces each font file with a non-empty directory of the same name.
   The download can no longer write the file, `Typeface.createFromFile()` fails,
   and the app uses the system font.

If Facebook adds a new font file name, add it to `FONT_FILES` in
[UseSystemFontPatch.java](extensions/extension/src/main/java/app/fblite/extension/UseSystemFontPatch.java).

## Build

Requires JDK 21 and a GitHub PAT with `read:packages`
(see [Morphe patcher setup](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md#-prepare-the-environment)):

```
./gradlew buildAndroid
```

The bundle is written to `patches/build/libs/*.mpp`.
Pushing to `main` builds and publishes a release with GitHub Actions.

## License

GPLv3, based on [morphe-patches-template](https://github.com/MorpheApp/morphe-patches-template).
