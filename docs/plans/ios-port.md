# Mako Scrubber for iOS — kickoff brief

Status: not started. This document is the starting point for the iOS port. It is written for
a fresh Claude Code session on the Mac that has cloned this repo
(`github.com/themakoway/mako-scrubber`) and has Xcode installed.

## What Mako Scrubber is

A local-only privacy tool. It strips identifying metadata (GPS location, camera make/model,
timestamps, software tags) from photos the user is about to share, and hands back a clean
copy. No account, no cloud, no upload, ever. GPLv3. It is the most-installed app in The Mako
Way suite and functions as the free loss-leader / funnel for the paid apps.

The Android app already exists in this repo (`app/`, package `com.mako.makoscrubber`).
Read these files first to understand the product behaviour you are matching:

- `README.md` — the promise and the trust claims
- `app/src/main/java/com/mako/makoscrubber/ScrubActivity.kt` — the scrub screen: audit
  report, strip, verification report, share-back
- `app/src/main/java/com/mako/makoscrubber/MainActivity.kt` — home dashboard: recently
  cleaned grid, multi-select share/delete, 30-day auto-expiry
- `app/src/main/java/com/mako/makoscrubber/MakoSettings.kt` — persisted lifetime scrub
  counter + store-review milestone logic
- `app/src/main/AndroidManifest.xml` — share-sheet registration, zero INTERNET permission
- `app/src/main/res/values*/strings.xml` — 10 localisations to reuse (see below)
- `docs/plans/android-video-scrubbing.md` — the video-scrubbing feature, being added to
  Android now; iOS should ship photo + video from day one

## Non-negotiable principles (these ARE the brand)

1. **No networking.** No URLSession calls, no analytics/crash SDKs, no third-party SPM
   packages that phone home. App Store privacy label must be "Data Not Collected". The
   Android app markets "no INTERNET permission"; the iOS equivalent is *demonstrably no
   network code* + the privacy label + open source.
2. **No accounts, no subscriptions, no ads, no IAP.** Same reasoning as Android — keep the
   Exodus/privacy-audit story clean. Monetisation is the funnel, not this app.
3. **Everything on-device.** Photos never leave the sandbox except through the user's own
   explicit share sheet.
4. **GPLv3.** Same licence as the repo. Any bundled code must be compatible.
5. **Match the Android UX beat-for-beat** where the platform allows: share-sheet entry,
   audit report -> one-tap scrub -> verification report, "recently cleaned" grid, 30-day
   auto-expiry, lifetime counter, store-review milestone prompt.

## Where the code lives

Put the iOS project in this same repo under `ios/` (monorepo). The Android Gradle build
lives under `app/` + root Gradle files and is unaffected by a sibling `ios/` folder. Shared
assets — the 10 translations, app icon source, changelog, privacy claims, screenshots — are
then in one place. Split into a dedicated repo later only if the monorepo becomes awkward.

Proposed layout:
```
ios/
  MakoScrubber.xcodeproj
  MakoScrubber/            # main SwiftUI app target
  ShareExtension/          # Action/Share extension target
  MakoScrubberTests/
  README.md                # iOS-specific build notes
```

## Android -> iOS mapping

| Android | iOS |
| --- | --- |
| `ScrubActivity` registered for `ACTION_SEND` `image/*` + `video/*` | **Share Extension** (`NSExtensionPointIdentifier = com.apple.share-services`) with `NSExtensionActivationRule` for `NSExtensionActivationSupportsImageWithMaxCount` + `...MovieWithMaxCount`. Also add an in-app picker via `PhotosPicker` for parity with the Android home-screen "Clean New" button. |
| Photo strip = decode Bitmap, re-encode JPEG | **ImageIO**: `CGImageSourceCreateWithURL` -> `CGImageDestinationCreateWithData` copying with the `kCGImagePropertyExifDictionary`, `kCGImagePropertyGPSDictionary`, `kCGImagePropertyTIFFDictionary`, `kCGImagePropertyIPTCDictionary`, and maker-note keys set to `kCFNull`. Preserves the encoded pixels for JPEG/HEIC (no quality loss). Keep the orientation tag. |
| Video strip = `MediaExtractor`/`MediaMuxer` remux (see android-video-scrubbing.md) | **`AVAssetExportSession`** with `presetName = AVAssetExportPresetPassthrough` (no re-encode) and `metadataItemFilter = AVMetadataItemFilter.forSharing()`. That filter is Apple's built-in "strip identifying metadata for sharing" — it removes location and similar container metadata while passing the media through untouched. This is the direct analog of the Android remux plan and is mostly free. Verify it also drops the QuickTime `mdta` location key and any timed-metadata (`mebx`) track; if not, additionally set `timedMetadataTrack` handling / drop non-A/V tracks via `AVMutableComposition`. |
| Save cleaned copy to `Pictures/MakoScrub` / `Movies/MakoScrub` via MediaStore | `PHPhotoLibrary` + `PHAssetCreationRequest`, added to a dedicated **"Mako Scrubber" album** (`PHAssetCollectionChangeRequest`). Requires `NSPhotoLibraryAddUsageDescription` (add) and `NSPhotoLibraryUsageDescription` (read, for the grid + expiry). |
| 30-day auto-expiry on launch (`deleteOldScrubbedImages`) | On app launch, fetch the album's assets, filter `creationDate` older than 30 days, `PHAssetChangeRequest.deleteAssets` (system shows the standard delete confirmation — acceptable). |
| "Recently cleaned" grid + multi-select share/delete | SwiftUI `LazyVGrid` over the album fetch result; `ShareLink` / `UIActivityViewController` for share; `PHAssetChangeRequest.deleteAssets` for delete. Video thumbnails come free from `PHImageManager`. |
| `MakoSettings` DataStore counter + review milestone | `UserDefaults` (in the App Group, so the Share Extension can increment it too) for the lifetime count; `StoreKit` `requestReview` / `AppStore.requestReview` at the same milestone thresholds (100, 500, 1000). |
| `strings.xml` x10 locales | A **String Catalog** (`Localizable.xcstrings`). Port the values from `app/src/main/res/values*/strings.xml`. Locales: `en`, `de`, `es`, `fr`, `it`, `ja`, `nl`, `pt-PT`, `pt-BR`, `zh-Hans`. |
| Compose UI + `MakoCoral` theme colour, `CauseFont` | SwiftUI. Pull the coral hex from `app/src/main/java/com/mako/makoscrubber/ui/theme/` and the font from wherever `CauseFont` is defined; reuse the same asset if licence permits, else a close system substitute. |

## Xcode project setup (first session tasks)

1. `git pull`, create and work on branch `ios/bootstrap`.
2. Create `ios/MakoScrubber.xcodeproj`: SwiftUI lifecycle app, **minimum deployment target
   iOS 16** (covers String Catalog back-deployment, `PhotosPicker`, `ShareLink`).
3. Add a second target: **Share Extension**.
4. Enable the **App Groups** capability on both targets (shared `UserDefaults` + a shared
   container if the extension needs to hand large files to the app). Suggested group id:
   `group.app.makoway.scrubber`.
5. Info.plist usage strings: `NSPhotoLibraryAddUsageDescription`,
   `NSPhotoLibraryUsageDescription` — short, honest, localised.
6. **No** networking entitlements. **No** SPM dependencies unless one proves unavoidable
   (justify in the PR if so).
7. Add `ios/README.md` with build/run instructions and the App Store Connect notes.
8. Add a GPLv3 header to new source files, matching the Android files' style.

## Feature parity checklist (v1)

- [ ] Share Extension accepts photos and videos (single + batch)
- [ ] Audit report: lists GPS / timestamp / make+model / software found in the input
- [ ] One-tap scrub: photos via ImageIO, videos via passthrough export + `forSharing()` filter
- [ ] Verification report: re-scan the output, show it clean
- [ ] Cleaned copies saved to the "Mako Scrubber" Photos album
- [ ] In-app home screen: "Clean New" picker + "recently cleaned" grid
- [ ] Multi-select share + delete from the grid
- [ ] 30-day auto-expiry of the album on launch
- [ ] Lifetime scrub counter (shared with the extension via App Group)
- [ ] Store-review milestone prompt at 100 / 500 / 1000
- [ ] 10 localisations ported
- [ ] Zero network code; App Privacy = Data Not Collected

## Verification

1. Build and run on a device (Simulator can't fully exercise Photos + share extension).
2. **iPhone photo with GPS**: Photos app -> Share -> Mako Scrubber. Audit lists GPS +
   timestamp. Scrub. Verification shows clean. AirDrop the output to a Mac, run `exiftool`
   — no GPS/Make/Model/Software, orientation intact, pixels unchanged (`exiftool -all`
   diff shows only metadata removed).
3. **iPhone .mov with Location Services on**: same flow. `ffprobe` / `exiftool` on the
   output — no `location`/`com.apple.quicktime.location.ISO6709`, no `mebx` metadata track,
   playback and rotation identical, duration unchanged, size ~same or slightly smaller.
4. **Batch**: select 5 mixed photos + videos, scrub all, confirm all land in the album.
5. **Expiry**: backdate an album asset's creation date (or wait), relaunch, confirm prompt +
   removal.
6. **Airplane mode**: whole flow works offline (it must — there should be nothing to break).
7. TestFlight build; confirm App Store Connect privacy questionnaire answers all "no".

## Open questions for Dave (resolve early)

- iOS bundle identifier — `app.makoway.scrubber` + `app.makoway.scrubber.ShareExtension`?
  (Android uses `com.mako.makoscrubber`; the website is `makoway.app`.)
- Apple Developer account / team ID to use for signing.
- App Store display name — "Mako Scrubber" (same as Android / Play Store)?
- Confirm iOS 16 minimum is acceptable, or go lower (iOS 15 loses String Catalog +
  `PhotosPicker` ergonomics) / higher (iOS 17 gains `.photosPicker` niceties).
- Keep in this repo under `ios/`, or spin up `mako-scrubber-ios` now?
- Is the `CauseFont` typeface licensed for redistribution in a second app binary?
