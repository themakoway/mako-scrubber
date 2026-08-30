package com.mako.makoscrubber

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.mako.makoscrubber.ui.theme.MakoScrubberTheme
import com.mako.makoscrubber.ui.theme.CauseFont
import com.mako.makoscrubber.ui.theme.MakoCoral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Calendar

class ScrubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val mediaUris = mutableListOf<Uri>()

        try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    uri?.let { mediaUris.add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    uris?.let { mediaUris.addAll(it) }
                }
                else -> {
                    intent.data?.let { mediaUris.add(it) }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_invalid_share), Toast.LENGTH_SHORT).show()
        }

        val isSharedIntent = intent.action == Intent.ACTION_SEND ||
                intent.action == Intent.ACTION_SEND_MULTIPLE

        setContent {
            MakoScrubberTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScrubAuditScreen(mediaUris, isSharedIntent)
                }
            }
        }
    }
}

@Composable
fun ScrubAuditScreen(mediaUris: List<Uri>, autoScrub: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as ScrubberApplication).settings

    val videoInputCount = remember(mediaUris) { mediaUris.count { context.isVideo(it) } }
    val allVideoInput = mediaUris.isNotEmpty() && videoInputCount == mediaUris.size
    val mixedInput = videoInputCount > 0 && videoInputCount < mediaUris.size

    val analyzingText = stringResource(R.string.analyzing)
    var auditResults by remember { mutableStateOf(analyzingText) }
    var scrubbedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isScrubbing by remember { mutableStateOf(false) }

    val resultKinds = remember(scrubbedUris) {
        scrubbedUris.mapNotNull { context.contentResolver.getType(it)?.substringBefore('/') }
    }
    val resultAllVideo = resultKinds.isNotEmpty() && resultKinds.all { it == "video" }
    val resultMixed = resultKinds.toSet().size > 1
    val scrollState = rememberScrollState()

    val initialTitle = stringResource(R.string.initial_audit)
    val verificationTitle = stringResource(R.string.verification_report)

    var hasWriteAccess by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasWriteAccess = granted }
    LaunchedEffect(Unit) {
        if (!hasWriteAccess) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    var showLargeWarning by remember { mutableStateOf(false) }

    val runScrub: () -> Unit = {
        if (!isScrubbing && mediaUris.isNotEmpty()) {
            isScrubbing = true
            scope.launch {
                val (results, videoFailures) = withContext(Dispatchers.IO) {
                    val r = mutableListOf<Uri>()
                    var failed = 0
                    mediaUris.forEach { uri ->
                        val isVideo = context.isVideo(uri)
                        val out = if (isVideo) {
                            scrubAndSaveVideo(context, uri)
                        } else {
                            scrubAndSaveImage(context, uri, estimatedSampleSize(context, uri))
                        }
                        if (out != null) r.add(out) else if (isVideo) failed++
                    }
                    r to failed
                }

                if (results.isNotEmpty()) {
                    settings.incrementScrubbedCount(results.size)
                }

                val verification = generateAuditReport(context, results, verificationTitle, isVerification = true)
                scrubbedUris = results
                auditResults = buildString {
                    append(auditResults)
                    append("\n---\n")
                    append(verification)
                    if (videoFailures > 0) {
                        append("\n")
                        append(context.getString(R.string.status_scrub_failed, videoFailures))
                    }
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isScrubbing = false
            }
        }
    }

    val startScrub: () -> Unit = {
        scope.launch {
            val oversized = withContext(Dispatchers.IO) {
                mediaUris.count { !context.isVideo(it) && estimatedSampleSize(context, it) > 1 }
            }
            if (oversized > 0) showLargeWarning = true else runScrub()
        }
    }

    LaunchedEffect(mediaUris, hasWriteAccess) {
        if (mediaUris.isNotEmpty()) {
            auditResults = withContext(Dispatchers.IO) {
                generateAuditReport(context, mediaUris, initialTitle)
            }
            if (autoScrub && hasWriteAccess && scrubbedUris.isEmpty() && !isScrubbing) {
                startScrub()
            }
        }
    }

    if (showLargeWarning) {
        AlertDialog(
            onDismissRequest = { showLargeWarning = false },
            title = { Text(stringResource(R.string.large_image_title), fontFamily = CauseFont, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.large_image_msg)) },
            confirmButton = {
                Button(
                    onClick = { showLargeWarning = false; runScrub() },
                    colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
                ) { Text(stringResource(R.string.btn_reduce_scrub), fontFamily = CauseFont, color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showLargeWarning = false }) {
                    Text(stringResource(R.string.cancel), fontFamily = CauseFont, color = Color.Gray)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(top = 24.dp)) {
                Text(
                    text = stringResource(R.string.audit_header),
                    fontFamily = CauseFont,
                    fontSize = 24.sp,
                    color = MakoCoral,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = auditResults,
                    fontFamily = CauseFont,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color.Gray, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (scrubbedUris.isEmpty()) {
                        Button(
                            onClick = {
                                if (!hasWriteAccess) {
                                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else if (mediaUris.isNotEmpty() && !isScrubbing) {
                                    startScrub()
                                }
                            },
                            enabled = !isScrubbing,
                            colors = ButtonDefaults.buttonColors(containerColor = MakoCoral),
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(vertical = 0.dp)
                        ) {
                            val label = when {
                                mediaUris.size == 1 && allVideoInput -> stringResource(R.string.scrub_video_1)
                                mediaUris.size == 1 -> stringResource(R.string.scrub_1)
                                mixedInput -> stringResource(R.string.scrub_media_n, mediaUris.size)
                                allVideoInput -> stringResource(R.string.scrub_video_n, mediaUris.size)
                                else -> stringResource(R.string.scrub_n, mediaUris.size)
                            }

                            Text(if (isScrubbing) stringResource(R.string.scrubbing) else label, color = Color.White, fontFamily = CauseFont, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                val shareType = when {
                                    resultMixed -> "*/*"
                                    resultAllVideo -> "video/mp4"
                                    else -> "image/jpeg"
                                }
                                val shareIntent = if (scrubbedUris.size == 1) {
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = shareType
                                        putExtra(Intent.EXTRA_STREAM, scrubbedUris[0])
                                    }
                                } else {
                                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = shareType
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(scrubbedUris))
                                    }
                                }
                                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title)))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MakoCoral),
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(vertical = 0.dp)
                        ) {
                            val label = when {
                                scrubbedUris.size == 1 && resultAllVideo -> stringResource(R.string.share_video_1)
                                scrubbedUris.size == 1 -> stringResource(R.string.share_1)
                                resultMixed -> stringResource(R.string.share_media_n, scrubbedUris.size)
                                resultAllVideo -> stringResource(R.string.share_video_n, scrubbedUris.size)
                                else -> stringResource(R.string.share_n, scrubbedUris.size)
                            }
                            Text(label, color = Color.White, fontFamily = CauseFont, fontSize = 13.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = { (context as? Activity)?.finish() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.cancel), color = Color.Gray, fontFamily = CauseFont, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        ScrubFooter()
    }
}

private suspend fun generateAuditReport(
    context: Context,
    uris: List<Uri>,
    title: String,
    isVerification: Boolean = false
): String = withContext(Dispatchers.IO) {
    val report = StringBuilder()
    val fileLabel = if (uris.size == 1) context.getString(R.string.label_file) else context.getString(R.string.label_files)

    report.append("$title (${uris.size} $fileLabel):\n\n")

    uris.forEachIndexed { index, uri ->
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType?.startsWith("image/") == true) {
            report.append(context.getString(R.string.label_file_header, index + 1) + "\n")
            try {
                context.contentResolver.openInputStream(uri).use { stream ->
                    if (stream != null) {
                        val exif = ExifInterface(stream)
                        var foundCount = 0

                        val gpsTags = listOf(
                            ExifInterface.TAG_GPS_LATITUDE,
                            ExifInterface.TAG_GPS_LONGITUDE,
                            ExifInterface.TAG_GPS_ALTITUDE,
                            ExifInterface.TAG_GPS_TIMESTAMP,
                            ExifInterface.TAG_GPS_DATESTAMP,
                            ExifInterface.TAG_GPS_AREA_INFORMATION,
                            ExifInterface.TAG_GPS_PROCESSING_METHOD
                        )
                        if (exif.latLong != null || gpsTags.any { !exif.getAttribute(it).isNullOrBlank() }) {
                            report.append("${context.getString(R.string.status_found)} ${context.getString(R.string.tag_gps)}\n")
                            foundCount++
                        }

                        val tags = listOf(
                            ExifInterface.TAG_MAKE to context.getString(R.string.tag_make),
                            ExifInterface.TAG_MODEL to context.getString(R.string.tag_model),
                            ExifInterface.TAG_DATETIME to context.getString(R.string.tag_timestamp),
                            ExifInterface.TAG_DATETIME_ORIGINAL to context.getString(R.string.tag_timestamp) + " (Original)",
                            ExifInterface.TAG_DATETIME_DIGITIZED to context.getString(R.string.tag_timestamp) + " (Digitized)",
                            ExifInterface.TAG_SOFTWARE to context.getString(R.string.tag_software),
                            ExifInterface.TAG_ARTIST to "Artist",
                            ExifInterface.TAG_COPYRIGHT to "Copyright",
                            ExifInterface.TAG_USER_COMMENT to "User Comment",
                            ExifInterface.TAG_IMAGE_DESCRIPTION to "Image Description",
                            ExifInterface.TAG_CAMERA_OWNER_NAME to "Camera Owner",
                            ExifInterface.TAG_BODY_SERIAL_NUMBER to "Body Serial Number",
                            ExifInterface.TAG_LENS_SERIAL_NUMBER to "Lens Serial Number",
                            ExifInterface.TAG_LENS_MAKE to "Lens Make",
                            ExifInterface.TAG_LENS_MODEL to "Lens Model",
                            ExifInterface.TAG_MAKER_NOTE to "Maker Note"
                        )

                        tags.forEach { (tag, label) ->
                            val value = exif.getAttribute(tag)
                            if (!value.isNullOrBlank()) {
                                report.append("${context.getString(R.string.status_found)} $label: ${value.take(15)}...\n")
                                foundCount++
                            }
                        }
                        if (foundCount == 0) {
                            report.append(context.getString(R.string.status_clean) + "\n")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                report.append(context.getString(R.string.status_error) + "\n")
            }
        } else if (mimeType?.startsWith("video/") == true) {
            report.append(context.getString(R.string.label_file_header, index + 1) + "\n")
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val found = context.getString(R.string.status_found)
                    var foundCount = 0

                    val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    if (!location.isNullOrBlank()) {
                        val pretty = formatIso6709(location) ?: location.trimEnd('/')
                        report.append("$found ${context.getString(R.string.tag_gps)}: $pretty\n")
                        foundCount++
                    }

                    // Same kind of free-text identity tags the image path scans EXIF for.
                    val textTags = listOf(
                        MediaMetadataRetriever.METADATA_KEY_AUTHOR to "Author",
                        MediaMetadataRetriever.METADATA_KEY_ARTIST to "Artist",
                        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "Album Artist",
                        MediaMetadataRetriever.METADATA_KEY_TITLE to "Title",
                        MediaMetadataRetriever.METADATA_KEY_COMPOSER to "Composer",
                        MediaMetadataRetriever.METADATA_KEY_WRITER to "Writer",
                        MediaMetadataRetriever.METADATA_KEY_ALBUM to "Album",
                        MediaMetadataRetriever.METADATA_KEY_GENRE to "Genre"
                    )
                    textTags.forEach { (key, label) ->
                        val value = retriever.extractMetadata(key)
                        if (!value.isNullOrBlank()) {
                            report.append("$found $label: ${value.take(20)}\n")
                            foundCount++
                        }
                    }

                    val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    if (!fps.isNullOrBlank()) {
                        val n = fps.toFloatOrNull()?.let { "%.0f".format(it) } ?: fps
                        report.append("$found Capture Frame Rate: $n fps\n")
                        foundCount++
                    }

                    val trackMimes = videoMetadataTrackMimes(context, uri)
                    if (trackMimes.isNotEmpty()) {
                        report.append("$found ${context.getString(R.string.tag_metadata_track)} (${trackMimes.joinToString(", ")})\n")
                        report.append("    ${context.getString(R.string.tag_metadata_track_desc)}\n")
                        foundCount++
                    }

                    // Capture time: the remux always restamps this to "now", so it is not a
                    // finding. Show it (with its value) as a note on the initial audit only,
                    // never counted, so the verification report can read clean.
                    if (!isVerification) {
                        val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                        if (!date.isNullOrBlank()) {
                            report.append("${context.getString(R.string.status_note)} ${context.getString(R.string.tag_video_timestamp)} — ${formatVideoDate(date)}\n")
                        }
                    }

                    if (foundCount == 0) {
                        report.append(context.getString(R.string.status_clean) + "\n")
                    }
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                report.append(context.getString(R.string.status_error) + "\n")
            }
        } else {
            report.append(context.getString(R.string.label_file_header, index + 1) + " " + context.getString(R.string.status_skip) + "\n")
        }
        report.append("\n")
    }
    return@withContext report.toString()
}

@Composable
fun ScrubFooter() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.copyright_format, year, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        Text(
            text = stringResource(R.string.about_mako),
            style = MaterialTheme.typography.labelLarge,
            color = MakoCoral,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.makoway.app")
            }
        )
    }
}

private fun Context.isVideo(uri: Uri): Boolean =
    contentResolver.getType(uri)?.startsWith("video/") == true

/** ISO-6709 ("+37.7749-122.4194/") -> "37.7749, -122.4194"; null if it doesn't parse. */
private fun formatIso6709(raw: String): String? {
    val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(raw) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    return "%.4f, %.4f".format(lat, lon)
}

/** MediaMetadataRetriever DATE ("20240115T143022.000Z") -> "2024-01-15 14:30:22". */
private fun formatVideoDate(raw: String): String {
    val g = Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})").find(raw)?.groupValues
        ?: return raw.take(24)
    return "${g[1]}-${g[2]}-${g[3]} ${g[4]}:${g[5]}:${g[6]}"
}

private fun estimatedSampleSize(context: Context, uri: Uri): Int {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        return 1
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) return 1

    val runtime = Runtime.getRuntime()
    val availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    val budget = availableHeap / 2

    var sampleSize = 1
    while (sampleSize < 16) {
        val bytesNeeded = (options.outWidth.toLong() / sampleSize) * (options.outHeight.toLong() / sampleSize) * 4 * 2
        if (bytesNeeded <= budget) break
        sampleSize *= 2
    }
    return sampleSize
}

private suspend fun scrubAndSaveImage(context: Context, uri: Uri, startSampleSize: Int = 1): Uri? = withContext(Dispatchers.IO) {
    return@withContext try {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        var bitmap: Bitmap? = null
        var inSampleSize = startSampleSize
        while (bitmap == null && inSampleSize <= 16) {
            try {
                val loadOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                val decoded = context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, loadOptions)
                } ?: return@withContext null
                bitmap = applyExifOrientation(decoded, orientation)
            } catch (e: OutOfMemoryError) {
                inSampleSize *= 2
            }
        }
        if (bitmap == null) return@withContext null

        val fileName = "MakoScrub_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MakoScrub")
            }
        }

        val outUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        outUri?.let { destination ->
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(destination)
            outputStream?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            destination
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}