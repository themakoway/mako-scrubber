# Mako Scrubber

Removes EXIF metadata from photos before you share them. Local-only. Zero INTERNET permission. GPLv3.

## What it does

Mako Scrubber strips GPS location, camera make/model, timestamps, and other identifying EXIF metadata from photos before you post or send them. Share it from any app, get clean copies back — no account, no cloud, no upload, ever.

## Screenshots

<p>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200">
</p>

## Features

- Strips GPS location, camera make/model, timestamp, and software tags from JPEG/HEIC photos
- Works from any app's share sheet (`Share > Mako Scrubber`)
- Batch scrub and share/delete multiple photos at once
- Cleaned photos auto-expire after 30 days
- No internet permission — the app cannot phone home even if it wanted to
- No accounts, no subscriptions, no ads

## Install

- **Play Store:** *(https://play.google.com/store/apps/details?id=com.mako.makoscrubber)*
- **F-Droid:** *pending*
- **Obtainium:** point it at this GitHub repo's Releases to track updates directly

## Verify our claims

- The `AndroidManifest.xml` in this repo requests no `INTERNET` permission — check for yourself in [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml).
- [Exodus Privacy report](https://reports.exodus-privacy.eu.org/en/reports/com.mako.makoscrubber/latest/) — 0 trackers detected. The 2 permissions listed (`CHECK_LICENSE`, a Play Store licensing check, and `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, an Android system self-permission) grant no access to your data.
- Full source is here. Build it yourself and compare against the signed release APK.

## Part of The Mako Way

Mako Scrubber is one app in [The Mako Way](https://www.makoway.app) suite — local-only tools with no accounts and no subscriptions, ever.

## Building

Standard Android Gradle project, no proprietary tooling required:

```
./gradlew assembleRelease
```

Requires JDK 17+ and the Android SDK (`compileSdk 36`, `minSdk 24`).

## License

Copyright (C) 2026 Dave Faris

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.

Full text in [LICENSE](LICENSE). Anyone shipping a modified version must publish their source.
