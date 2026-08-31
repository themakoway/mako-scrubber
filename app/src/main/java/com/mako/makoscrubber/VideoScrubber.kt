package com.mako.makoscrubber

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

private const val FALLBACK_BUFFER_SIZE = 1 shl 20 // 1 MB, used when a track has no KEY_MAX_INPUT_SIZE
private const val MAX_BUFFER_SIZE = 64 shl 20 // hard cap so a bogus KEY_MAX_INPUT_SIZE / grow loop can't OOM

/**
 * Remuxes [uri] into a fresh MP4 in Movies/MakoScrub with the container metadata dropped
 * (GPS, make/model, capture time, Apple/Android capture tags, timed-metadata tracks).
 *
 * The compressed audio/video samples are copied byte-for-byte — no decode, no re-encode, so
 * there is zero quality loss. Display rotation is the only thing carried over deliberately.
 *
 * Returns the output [Uri], or null if the muxer can't handle the file without re-encoding
 * (exotic codecs, unusual track layouts) — the half-written output is cleaned up first.
 * Honours coroutine cancellation: tapping Cancel mid-remux aborts and leaves nothing behind.
 */
suspend fun scrubAndSaveVideo(context: Context, uri: Uri): Uri? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val fileName = "MakoScrub_${System.currentTimeMillis()}.mp4"
    val active: () -> Boolean = { isActive }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MakoScrub")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        try {
            resolver.openFileDescriptor(outUri, "w")?.use { pfd ->
                remux(context, uri, active) { format -> MediaMuxer(pfd.fileDescriptor, format) }
            } ?: throw IllegalStateException("could not open output descriptor")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(outUri, values, null, null)
            outUri
        } catch (e: CancellationException) {
            runCatching { resolver.delete(outUri, null, null) }
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a hostile file can drive MediaMuxer/MediaExtractor to
            // OutOfMemoryError or a native-backed Error. Any of it means "can't remux this one".
            Log.w("MakoScrubber", "video scrub failed for $uri", e)
            runCatching { resolver.delete(outUri, null, null) }
            null
        }
    } else {
        // Pre-Q: MediaMuxer needs a real file path (no FileDescriptor ctor before API 26), and
        // MediaStore won't file the row under a sub-folder without an explicit DATA path — so
        // write to Movies/MakoScrub ourselves, then register it. Without this the dashboard
        // listing and the 30-day auto-cleanup would never see pre-Q output.
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "MakoScrub"
        ).apply { mkdirs() }
        val target = File(dir, fileName)
        try {
            remux(context, uri, active) { format -> MediaMuxer(target.absolutePath, format) }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
            }
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
                target.delete()
                null
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Throwable) {
            Log.w("MakoScrubber", "video scrub failed for $uri", e)
            target.delete()
            null
        }
    }
}

/**
 * MIME types of every track in [uri] that is neither video nor audio — iOS `mebx` timed
 * metadata, action-cam GPS/NMEA logs, per-frame timing tracks. Empty when there are none.
 * Used by the audit report; [scrubAndSaveVideo] drops all of these tracks.
 */
fun videoMetadataTrackMimes(context: Context, uri: Uri): List<String> {
    var extractor: MediaExtractor? = null
    return try {
        val ex = MediaExtractor().also { extractor = it }
        ex.setDataSource(context, uri, null)
        (0 until ex.trackCount).mapNotNull { i ->
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && !mime.startsWith("video/") && !mime.startsWith("audio/")) mime else null
        }
    } catch (e: Throwable) {
        emptyList()
    } finally {
        extractor?.release()
    }
}

private inline fun remux(
    context: Context,
    uri: Uri,
    isActive: () -> Boolean,
    muxerFactory: (Int) -> MediaMuxer
) {
    var extractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null
    try {
        val ex = MediaExtractor().also { extractor = it }
        ex.setDataSource(context, uri, null)
        muxer = muxerFactory(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackMap = HashMap<Int, Int>() // source track index -> output track index
        var bufferSize = FALLBACK_BUFFER_SIZE
        var rotation = 0

        for (i in 0 until ex.trackCount) {
            val format = ex.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            val isVideo = mime.startsWith("video/")
            if (!isVideo && !mime.startsWith("audio/")) continue

            if (isVideo && format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = format.getInteger(MediaFormat.KEY_ROTATION)
            }
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val declared = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (declared in (bufferSize + 1)..MAX_BUFFER_SIZE) bufferSize = declared
            }

            ex.selectTrack(i)
            trackMap[i] = muxer.addTrack(format)
        }
        if (trackMap.isEmpty()) throw IllegalStateException("no audio or video tracks")

        // Display orientation is not private — keep it. Never call muxer.setLocation().
        if (rotation != 0) muxer.setOrientationHint(rotation)
        muxer.start()

        var buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            if (!isActive()) throw CancellationException("scrub cancelled")

            val sourceTrack = ex.sampleTrackIndex
            if (sourceTrack < 0) break

            var sampleSize: Int
            while (true) {
                try {
                    sampleSize = ex.readSampleData(buffer, 0)
                    break
                } catch (e: RuntimeException) {
                    // Sample bigger than the buffer: IllegalArgumentException on most
                    // devices, BufferOverflowException on some. Grow and retry, but stop
                    // at MAX_BUFFER_SIZE so a corrupt sample size can't spin us into OOM.
                    if (bufferSize >= MAX_BUFFER_SIZE) throw e
                    bufferSize = (bufferSize * 2).coerceAtMost(MAX_BUFFER_SIZE)
                    buffer = ByteBuffer.allocate(bufferSize)
                }
            }
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = ex.sampleTime
            bufferInfo.flags = ex.sampleFlags.toMuxerFlags()
            muxer.writeSampleData(trackMap.getValue(sourceTrack), buffer, bufferInfo)
            ex.advance()
        }
        muxer.stop()
    } finally {
        runCatching { muxer?.release() }
        extractor?.release()
    }
}

private fun Int.toMuxerFlags(): Int {
    var flags = 0
    if (this and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        this and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0
    ) {
        flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return flags
}
