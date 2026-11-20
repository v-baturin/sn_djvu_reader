# DjVu Reader for Supernote Manta

DjVu Reader is a native Android app for the Supernote Manta. It opens bundled `.djvu` and `.djv` files directly, without converting them to PDF. The reading page uses the bundled DjVu.js decoder in an Android WebView. The app shell, file access, storage, and pen integration are written in Kotlin; React Native is no longer part of the app.

Launch the app and tap **Open DjVu** to choose a book. Its file browser shows DjVu files in the Manta's Document, INBOX, MyStyle, EXPORT, Note, and SCREENSHOT folders and their subfolders. Android's standard document picker was unavailable on the tested Manta firmware. Other apps may also pass a DjVu file through **Open with** if they provide a recognized DjVu MIME type.

Use **Fit Width** or **Fit Height** to size the page. **+** and **−** select manual zoom. Tap the page counter to jump to a page. Drag vertically with a finger to scroll a tall page; swipe horizontally to turn pages when the page fits across the screen. Draw only with the physical pen. Tap **Erase** to switch the pen to erasing, and tap it again to resume drawing. Double tap with one finger to undo the last stroke, or with two fingers to hide or show the toolbars. **Undo** removes the most recent stroke on a page; **Clear** removes all ink on that page after confirmation.

Ink is stored in the app's private storage for each file path; the DjVu file is never modified. The app reopens the last book at its saved page, zoom, scroll position, eraser setting, and toolbar state. Version 0.4.0 keeps the same storage keys and ink format as 0.3.3, so updating with `adb install -r` preserves previous annotations and reading state.

On Supernote devices that expose `drawPath` to apps, the pen's live stroke uses the device's low latency hardware ink preview. The app captures and saves the same stroke to its own ink canvas when the pen lifts. If `drawPath` is unavailable, it draws incrementally in the WebView. The `drawPath` protocol is undocumented by Android; this wrapper follows the published [Léamh device research](https://github.com/afluffywaffle/layuv/blob/native-port-drawpath-ink/leamh_tracker.md). A firmware update could change access to the service.

Recently viewed pages are kept in a bounded image cache. For bundled files up to 32 MB, a background decoder also prepares the previous and next pages. Prefetching can briefly use substantial CPU; the native app shell avoids the continuous React Native frame loop that previously ran while reading a static page.

## Build and install

Install a full JDK 17 or newer (including `javac`) and Android SDK Platform 35. Set `ANDROID_HOME` to the SDK location. No Node.js, npm, React Native, or Android NDK is required.

```sh
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The signed APK is `android/app/build/outputs/apk/release/app-release.apk`. For local distribution, copy it to `build/outputs/DjvuReader-android-v0.4.0.apk`. The APK uses the project's bundled debug signing key so it can update the previous build without clearing app data.

The repo includes DjVu.js 0.5.4 in `vendor/djvu.js`. The Android build packages it and `reader/reader.html` directly. The black-and-white launcher icon is generated from `assets/icon-source.png`; run `python3 scripts/generate_icons.py` with Pillow to regenerate Android density variants.

## Limits

- DjVu files must be single bundled files at most 256 MB. Indirect multi-file DjVu documents are not supported.
- The compressed file is loaded into WebView memory; pages are decoded on demand. Large high-resolution books can use substantial memory.
- Clearing app data or uninstalling the app removes saved ink.

## Licensing

DjVu.js is GPLv2; see `vendor/GNU_GPL_v2`. This combined app source is provided under GPLv2.
