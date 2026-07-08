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

        val imageUris = mutableListOf<Uri>()

        try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    uri?.let { imageUris.add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    uris?.let { imageUris.addAll(it) }
                }
                else -> {
                    intent.data?.let { imageUris.add(it) }
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
                    ScrubAuditScreen(imageUris, isSharedIntent)
                }
            }
        }
    }
}

@Composable
fun ScrubAuditScreen(imageUris: List<Uri>, autoScrub: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as ScrubberApplication).settings

    val analyzingText = stringResource(R.string.analyzing)
    var auditResults by remember { mutableStateOf(analyzingText) }
    var scrubbedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isScrubbing by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val initialTitle = stringResource(R.string.initial_audit)
    val verificationTitle = stringResource(R.string.verification_report)

    // MediaStore writes on Android 9 and below need WRITE_EXTERNAL_STORAGE at runtime
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
        if (!isScrubbing && imageUris.isNotEmpty()) {
            isScrubbing = true
            scope.launch {
                val results = withContext(Dispatchers.IO) {
                    val r = mutableListOf<Uri>()
                    imageUris.forEach { uri ->
                        scrubAndSaveImage(context, uri, estimatedSampleSize(context, uri))?.let { r.add(it) }
                    }
                    r
                }

                if (results.isNotEmpty()) {
                    settings.incrementScrubbedCount(results.size)
                }

                val verification = generateAuditReport(context, results, verificationTitle)
                scrubbedUris = results
                auditResults = "$auditResults\n---\n$verification"
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isScrubbing = false
            }
        }
    }

    // Warn and get consent before scrubbing anything too large to process at full resolution
    val startScrub: () -> Unit = {
        scope.launch {
            val oversized = withContext(Dispatchers.IO) {
                imageUris.count { estimatedSampleSize(context, it) > 1 }
            }
            if (oversized > 0) showLargeWarning = true else runScrub()
        }
    }

    LaunchedEffect(imageUris, hasWriteAccess) {
        if (imageUris.isNotEmpty()) {
            auditResults = withContext(Dispatchers.IO) {
                generateAuditReport(context, imageUris, initialTitle)
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
                                } else if (imageUris.isNotEmpty() && !isScrubbing) {
                                    startScrub()
                                }
                            },
                            enabled = !isScrubbing,
                            colors = ButtonDefaults.buttonColors(containerColor = MakoCoral),
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(vertical = 0.dp)
                        ) {
                            val label = if (imageUris.size == 1)
                                stringResource(R.string.scrub_1)
                            else
                                stringResource(R.string.scrub_n, imageUris.size)

                            Text(if (isScrubbing) stringResource(R.string.scrubbing) else label, color = Color.White, fontFamily = CauseFont, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                val shareIntent = if (scrubbedUris.size == 1) {
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "image/jpeg"
                                        putExtra(Intent.EXTRA_STREAM, scrubbedUris[0])
                                    }
                                } else {
                                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "image/jpeg"
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
                            val label = if (scrubbedUris.size == 1)
                                stringResource(R.string.share_1)
                            else
                                stringResource(R.string.share_n, scrubbedUris.size)
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

private suspend fun generateAuditReport(context: Context, uris: List<Uri>, title: String): String = withContext(Dispatchers.IO) {
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

                        // Any GPS data at all, not just latitude
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
                            // Standard EXIF field names, left untranslated
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

// Smallest power-of-two sample size at which the decoded bitmap plus its rotated
// copy fit comfortably in the free heap; 1 means full resolution is safe
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
        // BitmapFactory ignores the EXIF orientation tag, and scrubbing strips it —
        // bake the rotation into the pixels so the output isn't sideways
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        // Keep the original resolution when memory allows; halve it only if decoding
        // (or rotating, which needs a second copy) actually exhausts the heap
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