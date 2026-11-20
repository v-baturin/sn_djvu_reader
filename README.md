# DjVu Reader for Supernote Manta

DjVu Reader is a standalone Android app for the Supernote Manta. Launch it from the app list and tap **Open DjVu** to choose a `.djvu` or `.djv` book. It reads the original file without converting it to PDF or changing its size. The Supernote built-in reader is not involved.

The app's file browser shows DjVu files in the Manta's Document, INBOX, MyStyle, EXPORT, Note, and SCREENSHOT folders and their subfolders. Android's standard document picker is unavailable on the tested Manta firmware, so the app includes its own browser. If another app offers **Open with** for a DjVu file using a recognized DjVu MIME type, DjVu Reader can also receive that file.

Use **Fit Width** for reading at full width or **Fit Height** to see the entire page. The selected fit mode follows page turns; **+** and **−** switch to manual zoom. Tap the page counter to jump to a page. Draw directly with the pen. Drag vertically with a finger to scroll a tall page, or swipe horizontally to turn pages when the page fits across the screen. Tap **Erase** to switch the pen to erasing, and tap it again to resume drawing. Fingers never draw or erase. Double tap with one finger to undo the last ink stroke. Double tap with two fingers to hide or show the toolbars. **Undo** removes the most recent stroke on that page; **Clear** removes all ink on that page after confirmation. Ink is saved in the app's private storage for each file path. The DjVu file is never modified. These marks are separate from Supernote's native annotations. The app reopens the last book at its saved page, zoom, scroll position, eraser setting, and toolbar state.

On Supernote devices that expose `drawPath` to apps, the pen's live stroke uses the device's low latency hardware ink preview. The app captures and saves the same stroke to its own ink canvas when the pen lifts. If `drawPath` is unavailable, it draws incrementally in the WebView instead. The app does not change Android's hidden API policy.

Recently viewed pages are kept in a bounded image cache. For bundled files up to 32 MB, a background decoder also prepares the pages immediately before and after the current page, so returning to a nearby page usually avoids another full decode.

The `drawPath` protocol is undocumented by Android. This integration follows the published [Léamh device research](https://github.com/afluffywaffle/layuv/blob/native-port-drawpath-ink/leamh_tracker.md); the wrapper here is independently implemented. A Supernote firmware update could change access to the service, in which case the WebView drawing path remains available.

## Install

Build the APK as described below. With USB debugging enabled, install it using:

```sh
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Launch **DjVu Reader** from the device's app list.

## Build

Requires Node.js 18+, JDK 19+, Android SDK Platform 35, Android Build Tools 35.0.0, and Android NDK 27.1.12297006. Set `ANDROID_HOME` to your SDK location.

```sh
npm ci
python3 scripts/embed_reader.py
cd android
./gradlew assembleRelease
```

The built APK is `android/app/build/outputs/apk/release/app-release.apk`. The repo includes DjVu.js 0.5.4 in `vendor/djvu.js`.

The black-and-white launcher icon is generated from `assets/icon-source.png`. To regenerate its Android density variants, run `python3 scripts/generate_icons.py` (requires Pillow).

## Limits

- DjVu files must be single bundled files at most 256 MB. Indirect multi-file DjVu documents are not supported.
- The compressed file is loaded into the app's WebView memory; pages are decoded on demand. Large high-resolution books can use substantial memory.
- Ink remains in app storage. Clearing app data or uninstalling the app removes it.

## Licensing

DjVu.js is GPLv2; see `vendor/GNU_GPL_v2`. This combined app source is provided under GPLv2. React Native and its dependencies retain their own licenses.
