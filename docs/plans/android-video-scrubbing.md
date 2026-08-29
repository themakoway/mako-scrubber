# Add MP4/MOV metadata scrubbing to Mako Scrubber (Android)

Status: approved, not yet implemented.

## Context

Mako Scrubber currently strips EXIF from photos only (`ScrubActivity.scrubAndSaveImage`),
which works by decoding to a `Bitmap` and re-encoding JPEG so all metadata is dropped as a
side effect. Users share videos through the same channels and the app silently ignores them
(`generateAuditReport` marks non-`image/*` URIs as "skipped"). This adds video support that
strips container metadata (GPS, make/model, capture time, Apple/Android capture tags)
**without re-encoding** — the compressed audio/video samples are copied byte-for-byte into a
fresh MP4 container, so there is zero quality loss and the operation takes a few seconds.

Business framing (decided): ship free inside Scrubber, use the release as a cross-promo
moment for The Mako Way. Do **not** add in-app purchases — Play Billing would add the
`com.android.vending.BILLING` permission and muddy the "no INTERNET, clean Exodus report"
trust story that drives the app's word-of-mouth.

## Approach: remux via `MediaExtractor` -> `MediaMuxer`

No decode, no re-encode. Removes the container `udta`/`meta` atoms; we choose what goes
back in (rotation yes, location no).

### New file: `app/src/main/java/com/mako/makoscrubber/VideoScrubber.kt`

`suspend fun scrubAndSaveVideo(context, uri): Uri?` — mirrors `scrubAndSaveImage` shape
(returns output `Uri` or `null`, runs on `Dispatchers.IO`). Steps:

1. `MediaExtractor().setDataSource(context, uri, null)`.
2. Iterate tracks. Select **only** tracks whose `MediaFormat` MIME starts with `video/` or
   `audio/`. Skip everything else — this is what drops the iOS `mebx` timed-metadata track
   and action-cam GPS/NMEA tracks.
3. Create output via MediaStore (see below), open a `FileDescriptor`, construct
   `MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)`.
4. For the video track, read `MediaFormat.KEY_ROTATION` ("rotation-degrees") from the source
   format and call `muxer.setOrientationHint(degrees)` — display orientation is not private,
   keep it. **Never call `muxer.setLocation()`.**
5. `addTrack` for each kept track, `muxer.start()`.
6. Per-sample copy loop: `ByteBuffer` sized to `KEY_MAX_INPUT_SIZE` (fallback ~1 MB, grow on
   `BufferOverflow`); loop `readSampleData` / `writeSampleData` with
   `BufferInfo{presentationTimeUs, size, flags = extractor.sampleFlags}`, `advance()`.
7. `muxer.stop()` / `muxer.release()` / `extractor.release()`; clear `IS_PENDING`.
8. **Fallback:** wrap muxer calls in try/catch. If the muxer rejects the codec
   (`IllegalStateException` — e.g. AV1, Dolby Vision, multi-audio in exotic files), delete
   the pending MediaStore row and return `null`. The caller reports "couldn't scrub without
   re-encoding" for that file.

### MediaStore output (new helper, or generalise the pattern in `scrubAndSaveImage`)

Same pattern as images but `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`,
`MIME_TYPE = "video/mp4"`, `RELATIVE_PATH = "Movies/MakoScrub"` on API >= 29,
`DISPLAY_NAME = "MakoScrub_<millis>.mp4"`. Use `IS_PENDING = 1` during write, `0` after.

## Wiring changes

### `AndroidManifest.xml`
Add to the `ScrubActivity` intent-filter:
```xml
<data android:mimeType="video/*" />
```
(keeps the existing `image/*` line).

### `ScrubActivity.kt`
- `runScrub`: branch per URI on `contentResolver.getType(uri)` — `image/*` ->
  `scrubAndSaveImage`, `video/*` -> `scrubAndSaveVideo`. Keep results in one
  `scrubbedUris` list (mixed types allowed).
- Share intent after scrubbing: set `type = "*/*"` when the result set is mixed, else the
  specific type. Video-only single share -> `type = "video/mp4"`.
- `estimatedSampleSize` / `showLargeWarning` path is image-only; skip it for video URIs
  (remux is streaming, no heap pressure).
- `settings.incrementScrubbedCount(results.size)` already counts both — no change.

### `generateAuditReport` in `ScrubActivity.kt`
Add a `video/*` branch alongside the existing `image/*` branch. Use
`MediaMetadataRetriever`:
- `METADATA_KEY_LOCATION` -> report "GPS location" found.
- `METADATA_KEY_DATE` -> report "Timestamp".
- Presence of a non-A/V track (from `MediaExtractor`) -> report "Embedded metadata track".
- Nothing found -> existing `status_clean` line.
Re-run the same routine on the scrubbed output for the verification report (already how the
image path works — `generateAuditReport(context, results, verificationTitle)`).

### `MainActivity.kt` — dashboard, picker, expiry
- `loadScrubbedImages`: also query `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` with the
  `RELATIVE_PATH LIKE '%Movies/MakoScrub%'` (API >= 29) / `DATA LIKE '%/MakoScrub/%'`
  selection; merge with the image list, sort by `DATE_ADDED DESC`. Rename to
  `loadScrubbedMedia` for clarity.
- `deleteOldScrubbedImages`: add the same 30-day delete against the Video collection.
- Picker: change `launcher.launch("image/*")` — switch `GetMultipleContents` to
  `ActivityResultContracts.PickMultipleVisualMedia()` with
  `PickVisualMediaRequest(ImageAndVideo)` (works back to API 24 via the platform fallback),
  or keep `GetMultipleContents("*/*")`. Route picked URIs into `ScrubActivity` unchanged.
- Grid share intents (`onImageClick`, selection-mode share): use `type = "*/*"` when the
  selection is mixed.

### `app/build.gradle.kts`
- Remux itself needs **no new dependency**.
- Coil renders video thumbnails only with `io.coil-kt:coil-video:2.6.0` — add it and
  register `VideoFrameDecoder` (via an `ImageLoader` in `ScrubberApplication`, or
  `.decoderFactory(VideoFrameDecoder.Factory())` on each `AsyncImage`). ~40 KB, no native
  code, no INTERNET impact. Without it, video tiles show a blank/placeholder.

### Strings — `res/values/strings.xml` + 9 locale folders
New keys: video audit labels (`tag_video_location`, `tag_metadata_track`), scrub button
text for videos, the "couldn't scrub without re-encoding" fallback message, `status_skip`
reuse is fine for unsupported. Follow the existing per-locale XML-tag delivery format
(include `pt-BR` alongside `pt-PT`).

## What this removes vs. keeps

Removed: `©xyz` / ISO6709 GPS, `com.apple.quicktime.*`, make/model, `com.android.capture.fps`,
software tag, original `creation_time` (muxer restamps to now), iOS `mebx` / GPS tracks.
Kept identical: every video/audio sample (bit-for-bit), display rotation.
Not addressed (acceptable for v1, document in report): data burned into pixels (visible
timestamp overlay) and H.264/HEVC SEI messages — both require a re-encode and consumer
cameras don't put private data there.

## Verification

1. `./gradlew assembleDebug`, install on a device (API 24 + a modern API for coverage).
2. **iPhone .mov with Location Services on:** share -> Mako Scrubber. Audit report must list
   GPS + timestamp. Scrub. Verification report must show clean. Pull the output file, run
   `exiftool` / `ffprobe` — confirm no GPS/model/creation tags, confirm the QuickTime
   metadata track is gone, confirm `Rotation` matches the source.
3. **Android camera .mp4 (Google Camera, save-location on):** same checks.
4. Play both scrubbed files — no visual difference, audio in sync, correct orientation,
   duration unchanged. Compare file sizes (output slightly smaller is expected).
5. **Messenger-reencoded clip** (WhatsApp/Signal export) and a **4K60 HEVC** clip: confirm
   they remux without error.
6. **Unsupported codec** (AV1 clip, or a Dolby Vision .mov): confirm graceful "couldn't
   scrub without re-encoding" message, no zero-byte file left in `Movies/MakoScrub`.
7. Dashboard: scrubbed videos appear with thumbnails, share works, long-press multi-select
   + delete works, and a video older than 30 days is removed on next launch.
8. Batch `SEND_MULTIPLE` with mixed photos + videos in one share.

## Effort

~150–250 LOC for `VideoScrubber.kt` + audit branch; the manifest/dashboard/picker/strings
changes are mostly mechanical copies of the image path. The real cost is device/format
testing across steps 2–6 — budget roughly a week total, most of it testing and fallback
tuning.

## Alternative (fallback if remux proves lossy on real files)

Pure-Java `mp4parser` / isoparser (Apache-2.0, no native code, no INTERNET impact): deletes
only the `udta`/`meta` boxes and fixes offsets, leaving `mdat` byte-identical. Preserves
HDR10+/Dolby Vision dynamic metadata and multi-audio that `MediaMuxer` can drop. Cost: a
~300 KB dependency and box-tree code. Keep in reserve; start with remux.
