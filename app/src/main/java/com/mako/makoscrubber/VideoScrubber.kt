package com.mako.makoscrubber

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * (exotic codecs, unusual track layouts) — the pending MediaStore row is cleaned up first.
 */
suspend fun scrubAndSaveVideo(context: Context, uri: Uri): Uri? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val fileName = "MakoScrub_${System.currentTimeMillis()}.mp4"

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MakoScrub")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: return@withContext null

    var tempFile: File? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            resolver.openFileDescriptor(outUri, "w")?.use { pfd ->
                remux(context, uri) { format -> MediaMuxer(pfd.fileDescriptor, format) }
            } ?: throw IllegalStateException("could not open output descriptor")
        } else {
            // MediaMuxer's FileDescriptor constructor is API 26+; on 24/25 remux to a temp
            // file and copy the bytes into the MediaStore entry.
            val temp = File(context.cacheDir, fileName).also { tempFile = it }
            remux(context, uri) { format -> MediaMuxer(temp.absolutePath, format) }
            resolver.openOutputStream(outUri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("could not open output stream")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(outUri, values, null, null)
        }
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
    } finally {
        tempFile?.delete()
    }
}

/**
 * MIME types of every track in [uri] that is neither video nor audio — iOS `mebx` timed
 * metadata, action-cam GPS/NMEA logs, per-frame timing tracks. Empty when there are none.
 * Used by the audit report; [scrubAndSaveVideo] drops all of these tracks.
 */
fun videoMetadataTrackMimes(context: Context, uri: Uri): List<String> {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        (0 until extractor.trackCount).mapNotNull { i ->
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && !mime.startsWith("video/") && !mime.startsWith("audio/")) mime else null
        }
    } catch (e: Exception) {
        emptyList()
    } finally {
        extractor.release()
    }
}

private inline fun remux(context: Context, uri: Uri, muxerFactory: (Int) -> MediaMuxer) {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(context, uri, null)
        muxer = muxerFactory(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackMap = HashMap<Int, Int>() // source track index -> output track index
        var bufferSize = FALLBACK_BUFFER_SIZE
        var rotation = 0

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
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

            extractor.selectTrack(i)
            trackMap[i] = muxer.addTrack(format)
        }
        if (trackMap.isEmpty()) throw IllegalStateException("no audio or video tracks")

        // Display orientation is not private — keep it. Never call muxer.setLocation().
        if (rotation != 0) muxer.setOrientationHint(rotation)
        muxer.start()

        var buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val sourceTrack = extractor.sampleTrackIndex
            if (sourceTrack < 0) break

            var sampleSize: Int
            while (true) {
                try {
                    sampleSize = extractor.readSampleData(buffer, 0)
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
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags.toMuxerFlags()
            muxer.writeSampleData(trackMap.getValue(sourceTrack), buffer, bufferInfo)
            extractor.advance()
        }
        muxer.stop()
    } finally {
        runCatching { muxer?.release() }
        extractor.release()
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
