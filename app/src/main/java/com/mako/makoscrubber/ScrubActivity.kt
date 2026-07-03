package com.mako.makoscrubber

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    LaunchedEffect(imageUris) {
        withContext(Dispatchers.IO) {
            if (imageUris.isNotEmpty()) {
                val report = generateAuditReport(context, imageUris, initialTitle)
                withContext(Dispatchers.Main) {
                    auditResults = report
                }

                if (autoScrub && scrubbedUris.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isScrubbing = true
                    }
                    val results = mutableListOf<Uri>()
                    imageUris.forEach { uri ->
                        scrubAndSaveImage(context, uri)?.let { results.add(it) }
                    }

                    if (results.isNotEmpty()) {
                        settings.incrementScrubbedCount(results.size)
                    }

                    val verification = generateAuditReport(context, results, verificationTitle)

                    // Safely transition back to the main thread dispatcher before mutating UI state objects
                    withContext(Dispatchers.Main) {
                        scrubbedUris = results
                        auditResults = "$report\n---\n$verification"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isScrubbing = false
                    }
                }
            }
        }
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
                                if (imageUris.isNotEmpty() && !isScrubbing) {
                                    isScrubbing = true
                                    scope.launch {
                                        val results = mutableListOf<Uri>()
                                        imageUris.forEach { uri ->
                                            scrubAndSaveImage(context, uri)?.let { results.add(it) }
                                        }
                                        scrubbedUris = results

                                        if (results.isNotEmpty()) {
                                            settings.incrementScrubbedCount(results.size)
                                        }

                                        val verification = generateAuditReport(context, results, verificationTitle)
                                        auditResults = "$auditResults\n---\n$verification"
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isScrubbing = false
                                    }
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
                        val tags = listOf(
                            ExifInterface.TAG_GPS_LATITUDE to context.getString(R.string.tag_gps),
                            ExifInterface.TAG_MAKE to context.getString(R.string.tag_make),
                            ExifInterface.TAG_MODEL to context.getString(R.string.tag_model),
                            ExifInterface.TAG_DATETIME to context.getString(R.string.tag_timestamp),
                            ExifInterface.TAG_SOFTWARE to context.getString(R.string.tag_software)
                        )

                        var foundCount = 0
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

private suspend fun scrubAndSaveImage(context: Context, uri: Uri): Uri? = withContext(Dispatchers.IO) {
    return@withContext try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }

        val reqWidth = 4096
        val reqHeight = 4096
        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight: Int = options.outHeight / 2
            val halfWidth: Int = options.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val loadOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, loadOptions)
        } ?: return@withContext null

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