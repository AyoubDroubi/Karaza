<div align="center">

# 🍒 Karaza · كرزة

**A manga, manhwa and webtoon reader for Android with built-in AI translation — including Arabic.**

قارئ مانغا ومانهوا وويبتون لأندرويد، بترجمة ذكية مدمجة — وبالعربي.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-B5122B)](NOTICE.md)

</div>

## What it does

- **Reads everything**: online sources through extensions, plus local files.
- **Translates pages on the device**: text detection, OCR and text removal run on your phone; only the recognized text is sent to the AI provider you choose, with your own API key.
- **Arabic done right**: right-to-left typesetting, word-safe line breaking, an Arabic font, and translation tuned for natural manga dialogue.
- **Two ways to translate**: whole chapters in the background when they download, or page by page while you read.
- **Diagnostics**: test the AI connection and see recent translation errors from inside the app.

## Install

Download the latest APK from the [Releases](https://github.com/AyoubDroubi/Karaza/releases) or from a successful build on the
[Actions](https://github.com/AyoubDroubi/Karaza/actions) tab (Artifacts → `app-arm64-v8a-debug.apk` for most phones).
Requires Android 8.0 or newer.

## Set up translation

1. **Settings → Translation → Download models** (about 250 MB, once, on Wi-Fi).
2. Pick an **LLM provider** (for example Google Gemini) and paste your **API key**.
3. Set **Target language** to Arabic (or any other) and **Source language** to the language of the comic.
4. Use **Test AI connection** to confirm the key and model work.
5. Turn on **Translate chapters on download** and/or **Live translation**.

## Help

- **Storage**: choose where downloads and backups live in *Settings → Data and storage → Storage location*. Translated pages and their working files are kept inside each chapter folder.
- **Translation failed**: open *Settings → Translation → Recent translation errors*. Free API keys have per-minute and per-day limits; if many chapters fail at once, translate fewer chapters at a time or switch to a "lite" model.
- **Tracking**: connect AniList, MyAnimeList, Kitsu and others in *Settings → Tracking*, then add a title from its details page.
- **Cloudflare / site errors**: open the source in the built-in WebView, complete the check, then return to the app.
- **Moving a title to another source**: *Browse → Migrate*.
- **Problems or ideas**: [open an issue](https://github.com/AyoubDroubi/Karaza/issues).

## Build

```bash
git clone --recurse-submodules https://github.com/AyoubDroubi/Karaza.git
cd Karaza
./gradlew :app:assembleDebug
```

Needs JDK 21, the Android SDK, NDK `28.2.13676358` and CMake `3.22.1`.

## Credits and licenses

Karaza is free software under the **GPL-3.0**. It is built on open-source work, whose licenses and attribution are kept as
those licenses require — see [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE):

- [Mihon](https://github.com/mihonapp/mihon) (Apache-2.0) — the reader.
- [Yakuyomi](https://github.com/joyeli/Yakuyomi) and its engine (GPL-3.0) — on-device translation pipeline.
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) (GPL-3.0) — detection, OCR and inpainting models and prompt design.
- [FlexibleAdapter](https://github.com/arkon/FlexibleAdapter) (Apache-2.0) — vendored in `flexible-adapter/`.
- [Tajawal](https://fonts.google.com/specimen/Tajawal) font (SIL OFL 1.1).

This application hosts no content and has no affiliation with any content provider.
