package com.mako.makoscrubber

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.mako.makoscrubber.ui.theme.MakoScrubberTheme
import com.mako.makoscrubber.ui.theme.MakoCoral
import com.mako.makoscrubber.ui.theme.CauseFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("mako_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        val settings = (application as ScrubberApplication).settings

        if (hasStorageAccess(this)) {
            deleteOldScrubbedImages(this)
        }

        setContent {
            MakoScrubberTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val totalScrubbed by settings.totalScrubbedCount.collectAsState(initial = 0)
                    val hasAsked by settings.hasAskedForReview.collectAsState(initial = false)
                    val threshold by settings.reviewThreshold.collectAsState(initial = 100)

                    var showReviewPrompt by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(totalScrubbed, hasAsked, threshold) {
                        if (!hasAsked && totalScrubbed >= threshold) {
                            showReviewPrompt = true
                        }
                    }

                    if (showReviewPrompt) {
                        MakoReviewDialog(
                            threshold = threshold,
                            onDismiss = {
                                scope.launch {
                                    val nextTarget = when {
                                        threshold < 500 -> 500
                                        threshold < 1000 -> 1000
                                        else -> 999999
                                    }
                                    settings.setReviewThreshold(nextTarget)
                                    showReviewPrompt = false
                                }
                            },
                            onHandled = { permanent ->
                                scope.launch {
                                    if (permanent) settings.markReviewAsked()
                                    showReviewPrompt = false
                                }
                            }
                        )
                    }

                    MakoHome(isFirstLaunch) {
                        prefs.edit().putBoolean("is_first_launch", false).apply()
                    }
                }
            }
        }
    }
}

@Composable
fun MakoReviewDialog(
    threshold: Int,
    onDismiss: () -> Unit,
    onHandled: (Boolean) -> Unit
) {
    var stage by remember { mutableIntStateOf(1) }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (stage == 1) stringResource(R.string.milestone_title) else stringResource(R.string.feedback_title),
                fontFamily = CauseFont,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                when (stage) {
                    1 -> Text(stringResource(R.string.milestone_msg, threshold))
                    2 -> Text(stringResource(R.string.btn_leave_review) + "?")
                    3 -> {
                        Column {
                            Text(stringResource(R.string.feedback_msg) + "\n")
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Shield, contentDescription = null, tint = MakoCoral, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.privacy_notice),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                1 -> {
                    Row {
                        TextButton(onClick = { stage = 3 }) { Text(stringResource(R.string.btn_not_really), fontFamily = CauseFont) }
                        Button(
                            onClick = { stage = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
                        ) { Text(stringResource(R.string.btn_yes), fontFamily = CauseFont, color = Color.White) }
                    }
                }
                2 -> {
                    Button(
                        onClick = {
                            uriHandler.openUri("https://play.google.com/store/apps/details?id=com.mako.makoscrubber")
                            onHandled(true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
                    ) { Text(stringResource(R.string.btn_leave_review), fontFamily = CauseFont, color = Color.White) }
                }
                3 -> {
                    Button(
                        onClick = {
                            uriHandler.openUri("https://makoway.app/FEEDBACK.html?app=MAKO_SCRUBBER")
                            onHandled(true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
                    ) { Text(stringResource(R.string.btn_give_feedback), fontFamily = CauseFont, color = Color.White) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_maybe_later), fontFamily = CauseFont, color = Color.Gray)
            }
        }
    )
}

@Composable
fun MakoHome(initialFirstLaunch: Boolean, onActionTaken: () -> Unit) {
    var showOnboarding by remember { mutableStateOf(initialFirstLaunch) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scrubbedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val prefs = context.getSharedPreferences("mako_prefs", Context.MODE_PRIVATE)
    var showScrubTip by remember { mutableStateOf(false) }

    var hasStorageAccess by remember { mutableStateOf(hasStorageAccess(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasStorageAccess = grants.values.all { it }
        if (hasStorageAccess) scrubbedImages = loadScrubbedImages(context)
    }
    LaunchedEffect(Unit) {
        if (!hasStorageAccess) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) {
        scrubbedImages = loadScrubbedImages(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !showOnboarding && hasStorageAccess) {
                val oldSize = scrubbedImages.size
                scrubbedImages = loadScrubbedImages(context)

                if (scrubbedImages.isNotEmpty() && oldSize == 0 && !prefs.getBoolean("has_seen_tip", false)) {
                    showScrubTip = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onActionTaken()
            showOnboarding = false
            val intent = Intent(context, ScrubActivity::class.java).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                action = Intent.ACTION_SEND_MULTIPLE
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (showOnboarding) {
                OnboardingContent(onStart = {
                    onActionTaken()
                    showOnboarding = false
                })
            } else {
                DashboardContent(
                    images = scrubbedImages,
                    onScrubClick = { launcher.launch("image/*") },
                    onRefresh = { scrubbedImages = loadScrubbedImages(context) },
                    onImageClick = { uri ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title)))
                    },
                    onDeleteSelected = { uris ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            // System confirmation dialog; works even for rows this install doesn't own
                            try {
                                val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                                deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else {
                            uris.forEach {
                                try {
                                    context.contentResolver.delete(it, null, null)
                                } catch (e: SecurityException) {
                                    e.printStackTrace()
                                }
                            }
                            scrubbedImages = loadScrubbedImages(context)
                        }
                    },
                    showTip = showScrubTip,
                    onTipDismissed = {
                        showScrubTip = false
                        prefs.edit().putBoolean("has_seen_tip", true).apply()
                    }
                )
            }
        }
        MainFooter()
    }
}

@Composable
fun OnboardingContent(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_title),
            fontFamily = CauseFont,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MakoCoral
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_privacy_msg),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
        ) {
            Text(stringResource(R.string.get_started), color = Color.White, fontFamily = CauseFont)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardContent(
    images: List<Uri>,
    onScrubClick: () -> Unit,
    onRefresh: () -> Unit,
    onImageClick: (Uri) -> Unit,
    onDeleteSelected: (List<Uri>) -> Unit,
    showTip: Boolean,
    onTipDismissed: () -> Unit
) {
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }
    val isSelectionMode = selectedUris.isNotEmpty()

    val infiniteTransition = rememberInfiniteTransition(label = "wobble")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rotation"
    )

    BackHandler(enabled = isSelectionMode) {
        selectedUris = emptySet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(16.dp))

            if (!isSelectionMode) {
                Button(
                    onClick = onScrubClick,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MakoCoral)
                ) {
                    Text(stringResource(R.string.clean_new), color = Color.White, fontFamily = CauseFont)
                }
            } else {
                Spacer(modifier = Modifier.height(60.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(visible = showTip && !isSelectionMode) {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        text = stringResource(R.string.tip_long_press),
                        color = Color.Gray,
                        fontFamily = CauseFont,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { onTipDismissed() }
                    )
                }
            }

            Text(text = stringResource(R.string.recently_cleaned), fontFamily = CauseFont, fontSize = 18.sp, color = Color.Gray)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.LightGray)

            Text(
                text = stringResource(R.string.delete_auto_msg),
                fontFamily = CauseFont,
                fontSize = 10.sp,
                color = Color.Gray
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    scope.launch { onRefresh(); delay(800); isRefreshing = false }
                },
                modifier = Modifier.weight(1f)
            ) {
                if (images.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_images),
                            textAlign = TextAlign.Center,
                            color = Color.Gray,
                            fontFamily = CauseFont,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(images) { uri ->
                            val isSelected = selectedUris.contains(uri)
                            AsyncImage(
                                model = uri,
                                contentDescription = "Cleaned Photo",
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .graphicsLayer {
                                        rotationZ = if (isSelected) rotation else 0f
                                    }
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedUris = if (isSelected) selectedUris - uri else selectedUris + uri
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else {
                                                onImageClick(uri)
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedUris = selectedUris + uri
                                        }
                                    ),
                                contentScale = ContentScale.Crop,
                                alpha = if (isSelectionMode && !isSelected) 0.5f else 1.0f
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isSelectionMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                color = Color.White.copy(alpha = 0.7f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                shadowElevation = 12.dp
            ) {
                Box(modifier = Modifier.fillMaxSize().then(
                    if (Build.VERSION.SDK_INT >= 31) Modifier.blur(15.dp) else Modifier
                ))

                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MakoCoral)
                            .clickable { selectedUris = emptySet() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    Button(
                        onClick = { onDeleteSelected(selectedUris.toList()); selectedUris = emptySet() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MakoCoral),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.delete), color = Color.White, fontFamily = CauseFont, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/jpeg"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selectedUris))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title)))
                            selectedUris = emptySet()
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MakoCoral),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.share), color = Color.White, fontFamily = CauseFont, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MainFooter() {
    val uriHandler = LocalUriHandler.current
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
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
            modifier = Modifier.clickable { uriHandler.openUri("https://www.makoway.app") }
        )
    }
}

private fun hasStorageAccess(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private fun loadScrubbedImages(context: Context): List<Uri> {
    val uris = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    } else {
        "${MediaStore.Images.Media.DATA} LIKE ?"
    }
    val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf("%Pictures/MakoScrub%")
    } else {
        arrayOf("%/MakoScrub/%")
    }

    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return uris
}

private fun deleteOldScrubbedImages(context: Context) {
    val thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000
    val cutoffTime = (System.currentTimeMillis() - thirtyDaysInMillis) / 1000
    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?"
    } else {
        "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?"
    }
    val folderPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "%Pictures/MakoScrub%" else "%/MakoScrub/%"
    val selectionArgs = arrayOf(folderPath, cutoffTime.toString())
    try {
        context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
    } catch (e: Exception) { e.printStackTrace() }
}