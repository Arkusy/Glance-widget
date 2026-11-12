package com.example

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.ComicBlack
import com.example.ui.theme.ComicMutedInk
import com.example.ui.theme.ComicPanelGray
import com.example.ui.theme.ComicWhite
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.comicPanelFrame
import java.io.File
import kotlinx.coroutines.launch

/**
 * Companion activity featuring:
 * 1. Clean Comic Panel Header (`COMIC FILE WIDGET`), Storage Access status/button, and
 *    `Add Widget to Home Screen` button.
 * 2. Interactive **FILE BROWSER WIDGET PREVIEW (`COMIC INK`)** inside the app that works
 *    identically to [UnifiedComicWidget] — including folder navigation, file launching, and
 *    switching into **`IMAGE_VIEWER` mode** (with `← BACK`, `FIT / CROP` toggle, and tapping
 *    the image to open [FullscreenImageActivity]), while syncing live with pinned home screen widgets.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ComicCompanionAndPreviewScreen()
            }
        }
    }
}

@Composable
fun ComicCompanionAndPreviewScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasStorageAccess by remember {
        mutableStateOf(FileHelper.hasStoragePermission(context))
    }
    var currentPath by rememberSaveable {
        mutableStateOf(FileHelper.getRootStoragePath())
    }
    var widgetMode by rememberSaveable {
        mutableStateOf(UnifiedWidgetMode.BROWSER)
    }
    var selectedImagePath by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedTextPath by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var imageScaleMode by rememberSaveable {
        mutableStateOf(WidgetImageScaleMode.ZOOM_0_IN)
    }
    var showHidden by rememberSaveable {
        mutableStateOf(false)
    }
    var refreshTrigger by remember {
        mutableIntStateOf(0)
    }

    // Refresh permission status and update active home screen widgets when returning from Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorageAccess = FileHelper.hasStoragePermission(context)
                refreshTrigger++
                coroutineScope.launch {
                    UnifiedComicWidget().updateAll(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isAtRoot = FileHelper.isAtRootDirectory(currentPath)

    // System Back gesture:
    // - If viewing an image or text/markdown file, return to BROWSER mode in the current folder.
    // - If browsing a subfolder in BROWSER mode, navigate up to parent folder.
    BackHandler(
        enabled = widgetMode == UnifiedWidgetMode.IMAGE_VIEWER ||
            widgetMode == UnifiedWidgetMode.TEXT_VIEWER ||
            !isAtRoot
    ) {
        if (widgetMode == UnifiedWidgetMode.IMAGE_VIEWER || widgetMode == UnifiedWidgetMode.TEXT_VIEWER) {
            widgetMode = UnifiedWidgetMode.BROWSER
            imageScaleMode = WidgetImageScaleMode.ZOOM_0_IN
            coroutineScope.launch {
                syncStateToPinnedWidgets(
                    context = context,
                    mode = UnifiedWidgetMode.BROWSER,
                    currentPath = currentPath,
                    selectedImagePath = selectedImagePath,
                    selectedTextPath = selectedTextPath,
                    scaleMode = WidgetImageScaleMode.ZOOM_0_IN
                )
            }
        } else {
            val parent = FileHelper.getParentPath(currentPath)
            currentPath = parent
            coroutineScope.launch {
                syncStateToPinnedWidgets(
                    context = context,
                    mode = UnifiedWidgetMode.BROWSER,
                    currentPath = parent,
                    selectedImagePath = selectedImagePath,
                    selectedTextPath = selectedTextPath,
                    scaleMode = imageScaleMode
                )
            }
        }
    }

    val directoryResult = remember(hasStorageAccess, currentPath, showHidden, refreshTrigger) {
        FileHelper.listDirectoryContents(
            context = context,
            targetPath = currentPath,
            showHidden = showHidden
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = ComicWhite
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Header: Simple, well-aligned title
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .comicPanelFrame(backgroundColor = ComicWhite, borderColor = ComicBlack)
                        .padding(vertical = 18.dp, horizontal = 16.dp)
                        .testTag("main_header_card"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.main_header_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = ComicBlack,
                        textAlign = TextAlign.Center
                    )
                }

                // 2. Storage Access Card + Add Widget Action Row
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .comicPanelFrame(backgroundColor = ComicPanelGray, borderColor = ComicBlack)
                            .padding(14.dp)
                            .testTag("storage_access_card"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasStorageAccess) {
                            Text(
                                text = stringResource(R.string.storage_access_active),
                                style = MaterialTheme.typography.titleMedium,
                                color = ComicBlack,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("storage_access_active_text")
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .comicPanelFrame(backgroundColor = ComicBlack, borderColor = ComicBlack)
                                    .clickable {
                                        FileHelper.launchStoragePermissionSettings(context)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("grant_storage_access_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_grant_storage_access),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ComicWhite,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .comicPanelFrame(backgroundColor = ComicBlack, borderColor = ComicBlack)
                            .clickable {
                                requestPinUnifiedComicWidget(context)
                            }
                            .padding(horizontal = 16.dp, vertical = 13.dp)
                            .testTag("add_widget_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.btn_add_widget_home_screen),
                            style = MaterialTheme.typography.titleMedium,
                            color = ComicWhite,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // 3. FILE BROWSER WIDGET PREVIEW (including IMAGE_VIEWER and TEXT_VIEWER modes)
                ComicWidgetInteractivePreviewSection(
                    mode = widgetMode,
                    currentPath = currentPath,
                    isAtRoot = isAtRoot,
                    showHidden = showHidden,
                    selectedImagePath = selectedImagePath,
                    selectedTextPath = selectedTextPath,
                    imageScaleMode = imageScaleMode,
                    directoryResult = directoryResult,
                    onNavigateFolder = { targetFolder ->
                        val normalized = FileHelper.normalizePath(targetFolder)
                        currentPath = normalized
                        widgetMode = UnifiedWidgetMode.BROWSER
                        coroutineScope.launch {
                            syncStateToPinnedWidgets(
                                context = context,
                                mode = UnifiedWidgetMode.BROWSER,
                                currentPath = normalized,
                                selectedImagePath = selectedImagePath,
                                selectedTextPath = selectedTextPath,
                                scaleMode = imageScaleMode
                            )
                        }
                    },
                    onNavigateUp = {
                        val parent = FileHelper.getParentPath(currentPath)
                        currentPath = parent
                        widgetMode = UnifiedWidgetMode.BROWSER
                        coroutineScope.launch {
                            syncStateToPinnedWidgets(
                                context = context,
                                mode = UnifiedWidgetMode.BROWSER,
                                currentPath = parent,
                                selectedImagePath = selectedImagePath,
                                selectedTextPath = selectedTextPath,
                                scaleMode = imageScaleMode
                            )
                        }
                    },
                    onNavigateRoot = {
                        val root = FileHelper.getRootStoragePath()
                        currentPath = root
                        widgetMode = UnifiedWidgetMode.BROWSER
                        coroutineScope.launch {
                            syncStateToPinnedWidgets(
                                context = context,
                                mode = UnifiedWidgetMode.BROWSER,
                                currentPath = root,
                                selectedImagePath = selectedImagePath,
                                selectedTextPath = selectedTextPath,
                                scaleMode = imageScaleMode
                            )
                        }
                    },
                    onRefresh = {
                        refreshTrigger++
                        coroutineScope.launch {
                            UnifiedComicWidget().updateAll(context)
                        }
                    },
                    onToggleHidden = {
                        showHidden = !showHidden
                    },
                    onOpenImageInWidget = { imagePath ->
                        selectedImagePath = imagePath
                        imageScaleMode = WidgetImageScaleMode.ZOOM_0_IN
                        widgetMode = UnifiedWidgetMode.IMAGE_VIEWER
                        coroutineScope.launch {
                            syncStateToPinnedWidgets(
                                context = context,
                                mode = UnifiedWidgetMode.IMAGE_VIEWER,
                                currentPath = currentPath,
                                selectedImagePath = imagePath,
                                selectedTextPath = selectedTextPath,
                                scaleMode = WidgetImageScaleMode.ZOOM_0_IN
                            )
                        }
                    },
                    onReturnToBrowser = {
                        widgetMode = UnifiedWidgetMode.BROWSER
                        imageScaleMode = WidgetImageScaleMode.ZOOM_0_IN
                        coroutineScope.launch {
                            syncStateToPinnedWidgets(
                                context = context,
                                mode = UnifiedWidgetMode.BROWSER,
                                currentPath = currentPath,
                                selectedImagePath = selectedImagePath,
                                selectedTextPath = selectedTextPath,
                                scaleMode = WidgetImageScaleMode.ZOOM_0_IN
                            )
                        }
                    },
                    onToggleImageScaleMode = {
                        val nextScale = imageScaleMode.toggled()
                        imageScaleMode = nextScale
                        coroutineScope.launch {
                            syncStateToPinnedWidgets(
                                context = context,
                                mode = UnifiedWidgetMode.IMAGE_VIEWER,
                                currentPath = currentPath,
                                selectedImagePath = selectedImagePath,
                                selectedTextPath = selectedTextPath,
                                scaleMode = nextScale
                            )
                        }
                    },
                    onLaunchFullscreenImage = { imagePath ->
                        val intent = Intent(context, FullscreenImageActivity::class.java).apply {
                            putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, imagePath)
                        }
                        context.startActivity(intent)
                    },
                    onOpenNonImageFile = { item ->
                        val targetFile = File(item.absolutePath)
                        if (FileHelper.isValidTextFile(targetFile)) {
                            selectedTextPath = targetFile.absolutePath
                            widgetMode = UnifiedWidgetMode.TEXT_VIEWER
                            coroutineScope.launch {
                                syncStateToPinnedWidgets(
                                    context = context,
                                    mode = UnifiedWidgetMode.TEXT_VIEWER,
                                    currentPath = currentPath,
                                    selectedImagePath = selectedImagePath,
                                    selectedTextPath = targetFile.absolutePath,
                                    scaleMode = imageScaleMode
                                )
                            }
                        } else {
                            FileHelper.openFileExternally(context, targetFile)
                        }
                    },
                    onOpenWithFilePicker = { filePath ->
                        FileHelper.openFileWithPicker(context, File(filePath))
                    },
                    onGrantPermission = {
                        FileHelper.launchStoragePermissionSettings(context)
                    }
                )
            }
        }
    }
}

/**
 * In-app interactive Comic Panel File Browser, Image Viewer & Text/Markdown Viewer Widget Preview
 * matching the user's preferred layout (`FILE BROWSER WIDGET PREVIEW` + `COMIC INK` badge) and
 * supporting `BROWSER`, `IMAGE_VIEWER`, and `TEXT_VIEWER` modes identically to [UnifiedComicWidget].
 */
@Composable
private fun ComicWidgetInteractivePreviewSection(
    mode: UnifiedWidgetMode,
    currentPath: String,
    isAtRoot: Boolean,
    showHidden: Boolean,
    selectedImagePath: String?,
    selectedTextPath: String?,
    imageScaleMode: WidgetImageScaleMode,
    directoryResult: DirectoryLoadResult,
    onNavigateFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateRoot: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpenImageInWidget: (String) -> Unit,
    onReturnToBrowser: () -> Unit,
    onToggleImageScaleMode: () -> Unit,
    onLaunchFullscreenImage: (String) -> Unit,
    onOpenNonImageFile: (FileBrowserItem) -> Unit,
    onOpenWithFilePicker: (String) -> Unit,
    onGrantPermission: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Section Header: FILE BROWSER WIDGET PREVIEW + [COMIC INK] badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FILE BROWSER WIDGET PREVIEW",
                style = MaterialTheme.typography.titleLarge,
                color = ComicBlack
            )
            Box(
                modifier = Modifier
                    .background(ComicBlack)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "COMIC INK",
                    style = MaterialTheme.typography.labelSmall,
                    color = ComicWhite
                )
            }
        }

        // Outer 2.dp Black Comic Panel Frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .comicPanelFrame(backgroundColor = ComicWhite, borderColor = ComicBlack)
                .padding(12.dp)
                .testTag("widget_preview_container")
        ) {
            if (mode == UnifiedWidgetMode.IMAGE_VIEWER && !selectedImagePath.isNullOrBlank()) {
                InAppComicImageViewerMode(
                    imagePath = selectedImagePath,
                    scaleMode = imageScaleMode,
                    onBackToBrowser = onReturnToBrowser,
                    onToggleScaleMode = onToggleImageScaleMode,
                    onTapImageFullscreen = { onLaunchFullscreenImage(selectedImagePath) }
                )
            } else if (mode == UnifiedWidgetMode.TEXT_VIEWER && !selectedTextPath.isNullOrBlank()) {
                InAppComicTextViewerMode(
                    textPath = selectedTextPath,
                    onBackToBrowser = onReturnToBrowser,
                    onOpenWithFilePicker = { onOpenWithFilePicker(selectedTextPath) }
                )
            } else {
                InAppComicBrowserMode(
                    currentPath = currentPath,
                    isAtRoot = isAtRoot,
                    showHidden = showHidden,
                    directoryResult = directoryResult,
                    onNavigateFolder = onNavigateFolder,
                    onNavigateUp = onNavigateUp,
                    onNavigateRoot = onNavigateRoot,
                    onRefresh = onRefresh,
                    onToggleHidden = onToggleHidden,
                    onOpenImageInWidget = onOpenImageInWidget,
                    onOpenNonImageFile = onOpenNonImageFile,
                    onGrantPermission = onGrantPermission
                )
            }
        }
    }
}

@Composable
private fun InAppComicBrowserMode(
    currentPath: String,
    isAtRoot: Boolean,
    showHidden: Boolean,
    directoryResult: DirectoryLoadResult,
    onNavigateFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateRoot: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpenImageInWidget: (String) -> Unit,
    onOpenNonImageFile: (FileBrowserItem) -> Unit,
    onGrantPermission: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Divided Comic Panel Header Bar (matching the exact layout from the screenshot)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .border(2.dp, ComicBlack)
                .background(ComicPanelGray),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back / Up Cell
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(enabled = !isAtRoot, onClick = onNavigateUp)
                    .testTag("widget_nav_up_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_comic_back),
                    contentDescription = "Up to parent folder",
                    modifier = Modifier.size(20.dp),
                    alpha = if (isAtRoot) 0.28f else 1f
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            // Folder Title & Path Cell
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = FileHelper.getDisplayFolderName(currentPath).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = ComicBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("current_folder_title")
                )
                Text(
                    text = FileHelper.getTruncatedDisplayPath(currentPath),
                    style = MaterialTheme.typography.labelSmall,
                    color = ComicMutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("current_folder_path")
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            // Hidden Files Toggle Cell
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(onClick = onToggleHidden)
                    .testTag("widget_toggle_hidden_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (showHidden) {
                        Icons.Default.Visibility
                    } else {
                        Icons.Default.VisibilityOff
                    },
                    contentDescription = "Toggle hidden files",
                    tint = ComicBlack,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            // Home / Root Cell
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(onClick = onNavigateRoot)
                    .testTag("widget_nav_root_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_comic_home),
                    contentDescription = "Jump to root folder",
                    modifier = Modifier.size(20.dp)
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            // Refresh Cell
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(onClick = onRefresh)
                    .testTag("widget_nav_refresh_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_comic_refresh),
                    contentDescription = "Refresh directory",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Directory Content
        when (directoryResult) {
            is DirectoryLoadResult.PermissionRequired -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 16.dp)
                        .testTag("widget_permission_fallback"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_lock_storage),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "STORAGE ACCESS NEEDED",
                        style = MaterialTheme.typography.titleMedium,
                        color = ComicBlack
                    )
                    Box(
                        modifier = Modifier
                            .comicPanelFrame(backgroundColor = ComicBlack, borderColor = ComicBlack)
                            .clickable(onClick = onGrantPermission)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("widget_grant_permission_button")
                    ) {
                        Text(
                            text = stringResource(R.string.btn_grant_storage_access),
                            style = MaterialTheme.typography.labelLarge,
                            color = ComicWhite
                        )
                    }
                }
            }

            is DirectoryLoadResult.Unreadable -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.widget_unreadable_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ComicBlack
                    )
                    Text(
                        text = directoryResult.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ComicMutedInk
                    )
                    Box(
                        modifier = Modifier
                            .comicPanelFrame(backgroundColor = ComicBlack, borderColor = ComicBlack)
                            .clickable(onClick = onNavigateRoot)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("widget_return_root_button")
                    ) {
                        Text(
                            text = stringResource(R.string.widget_reset_root_button),
                            style = MaterialTheme.typography.labelLarge,
                            color = ComicWhite
                        )
                    }
                }
            }

            is DirectoryLoadResult.Empty -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 16.dp)
                        .testTag("widget_empty_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_folder_empty),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.widget_empty_folder_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ComicBlack
                    )
                }
            }

            is DirectoryLoadResult.Success -> {
                // Summary Strip: "28 DIRS • 34 FILES" on left, "FOLDERS FIRST (A–Z)" on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${directoryResult.totalFoldersCount} DIRS • ${directoryResult.totalFilesCount} FILES",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicBlack
                    )
                    Text(
                        text = "FOLDERS FIRST (A–Z)",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicMutedInk
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp, max = 460.dp)
                        .testTag("widget_directory_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = directoryResult.items,
                        key = { it.absolutePath }
                    ) { item ->
                        InAppComicFileRow(
                            item = item,
                            onClick = {
                                when {
                                    item.isDirectory -> onNavigateFolder(item.absolutePath)
                                    item.isImage -> onOpenImageInWidget(item.absolutePath)
                                    else -> onOpenNonImageFile(item)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InAppComicFileRow(
    item: FileBrowserItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .comicPanelFrame(backgroundColor = ComicWhite, borderColor = ComicBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("file_row_${item.name}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = item.category.iconRes),
                contentDescription = item.category.badgeLabel,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.isDirectory) item.name.uppercase() else item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = ComicBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.formattedSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ComicMutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (item.isDirectory) {
                Image(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = "Enter folder",
                    modifier = Modifier.size(18.dp)
                )
            } else {
                val badgeText = FileHelper.getFileFormatBadge(File(item.absolutePath))
                Box(
                    modifier = Modifier
                        .background(ComicBlack)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicWhite
                    )
                }
            }
        }
    }
}

/**
 * In-app `IMAGE_VIEWER` mode inside the widget preview box:
 * - Left: Back icon button (`ic_comic_back`) to return immediately to `BROWSER` mode in the exact same folder.
 * - Center: Image filename on top + folder path below (matching the header layout).
 * - Right: 2-step Zoom-In (`ic_comic_zoom_in`) / 2-step Zoom-Out (`ic_comic_zoom_out`) icon button.
 * - Tapping the displayed image opens [FullscreenImageActivity] for pinch-to-zoom and panning.
 */
@Composable
private fun InAppComicImageViewerMode(
    imagePath: String,
    scaleMode: WidgetImageScaleMode,
    onBackToBrowser: () -> Unit,
    onToggleScaleMode: () -> Unit,
    onTapImageFullscreen: () -> Unit
) {
    val file = remember(imagePath) { File(imagePath) }
    val parentFolderPath = remember(imagePath) {
        file.parentFile?.absolutePath ?: FileHelper.getRootStoragePath()
    }
    val truncatedPath = remember(parentFolderPath) {
        FileHelper.getTruncatedDisplayPath(parentFolderPath)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("in_app_image_viewer_mode"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Divided Comic Panel Image Viewer Header Bar:
        // [Back Icon] | Image Name + Path | [Zoom In (2x) / Zoom Out (2x) Icon]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .border(2.dp, ComicBlack)
                .background(ComicPanelGray),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Icon Cell
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(onClick = onBackToBrowser)
                    .testTag("image_viewer_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_comic_back),
                    contentDescription = "Back to folder",
                    modifier = Modifier.size(20.dp)
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            // Image Name & Path Cell
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = file.name.ifBlank { "IMAGE" },
                    style = MaterialTheme.typography.titleMedium,
                    color = ComicBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("image_viewer_title")
                )
                Text(
                    text = truncatedPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = ComicMutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("image_viewer_path")
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            // Zoom In (2x) / Zoom Out (2x) Icon Cell
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(onClick = onToggleScaleMode)
                    .testTag("image_viewer_scale_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(
                        id = if (scaleMode.showZoomOutIcon) {
                            R.drawable.ic_comic_zoom_out
                        } else {
                            R.drawable.ic_comic_zoom_in
                        }
                    ),
                    contentDescription = if (scaleMode.showZoomOutIcon) {
                        "Zoom out"
                    } else {
                        "Zoom in"
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Displayed Image Frame (Tap to open FullscreenImageActivity)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .comicPanelFrame(backgroundColor = ComicWhite, borderColor = ComicBlack)
                .clickable(onClick = onTapImageFullscreen)
                .testTag("image_viewer_canvas"),
            contentAlignment = Alignment.Center
        ) {
            val containerAspect = if (maxWidth.value > 0f && maxHeight.value > 0f) {
                (maxWidth.value / maxHeight.value).coerceIn(0.25f, 4.0f)
            } else {
                1.0f
            }
            val bitmap = remember(imagePath, scaleMode.zoomStep, containerAspect) {
                FileHelper.loadZoomedBitmap(
                    filePath = imagePath,
                    zoomFactor = scaleMode.zoomFactor,
                    containerAspect = containerAspect,
                    maxDimension = 1080
                )
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "IMAGE UNAVAILABLE",
                    style = MaterialTheme.typography.titleMedium,
                    color = ComicBlack
                )
            }
        }

        Text(
            text = "TAP IMAGE FOR FULLSCREEN PINCH-TO-ZOOM",
            style = MaterialTheme.typography.labelSmall,
            color = ComicMutedInk,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

/**
 * In-app `TEXT_VIEWER` mode inside the widget preview box:
 * - Left: Back icon button (`ic_comic_back`) to return immediately to `BROWSER` mode in the exact same folder.
 * - Center: File name on top + folder path below.
 * - Right: `MD` or `TXT` mode badge.
 * - Body:
 *   - Enforces the `1 MB` preview limit; if exceeded, displays an "OPEN WITH FILE PICKER" button.
 *   - For `.md` files, renders rich styled Markdown via [ComposeMarkdownBlockItem].
 *   - For `.txt` and code files (`.java`, `.c`, `.kt`, `.py`, etc.), renders scrollable monospace text.
 */
@Composable
private fun InAppComicTextViewerMode(
    textPath: String,
    onBackToBrowser: () -> Unit,
    onOpenWithFilePicker: () -> Unit
) {
    val file = remember(textPath) { File(textPath) }
    val parentFolderPath = remember(textPath) {
        file.parentFile?.absolutePath ?: FileHelper.getRootStoragePath()
    }
    val truncatedPath = remember(parentFolderPath) {
        FileHelper.getTruncatedDisplayPath(parentFolderPath)
    }
    val isMarkdown = remember(textPath) {
        FileHelper.isMarkdownFile(file)
    }
    val loadResult = remember(textPath) {
        FileHelper.loadTextFileForPreview(textPath)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("in_app_text_viewer_mode"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Divided Comic Panel Text Viewer Header Bar:
        // [Back Icon] | File Name + Path | [MD / TXT Badge]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .border(2.dp, ComicBlack)
                .background(ComicPanelGray),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .clickable(onClick = onBackToBrowser)
                    .testTag("text_viewer_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_comic_back),
                    contentDescription = "Back to folder",
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = file.name.ifBlank { "DOCUMENT" },
                    style = MaterialTheme.typography.titleMedium,
                    color = ComicBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("text_viewer_title")
                )
                Text(
                    text = truncatedPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = ComicMutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("text_viewer_path")
                )
            }

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(ComicBlack)
            )

            Box(
                modifier = Modifier
                    .width(54.dp)
                    .fillMaxHeight()
                    .background(ComicWhite)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(ComicBlack)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = FileHelper.getFileFormatBadge(file),
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicWhite
                    )
                }
            }
        }

        // Scrollable Text / Markdown Content Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 440.dp)
                .comicPanelFrame(backgroundColor = ComicWhite, borderColor = ComicBlack)
                .testTag("text_viewer_content_box")
        ) {
            when (loadResult) {
                is TextFileLoadResult.TooLargeForPreview -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 18.dp)
                            .testTag("text_viewer_too_large_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "FILE EXCEEDS 1 MB LIMIT",
                            style = MaterialTheme.typography.titleMedium,
                            color = ComicBlack,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${loadResult.fileName} (${loadResult.formattedSize} > 1.0 MB max preview size)",
                            style = MaterialTheme.typography.bodySmall,
                            color = ComicMutedInk,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .comicPanelFrame(backgroundColor = ComicBlack, borderColor = ComicBlack)
                                .clickable(onClick = onOpenWithFilePicker)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("open_with_file_picker_button")
                        ) {
                            Text(
                                text = "OPEN WITH FILE PICKER",
                                style = MaterialTheme.typography.labelLarge,
                                color = ComicWhite
                            )
                        }
                    }
                }

                is TextFileLoadResult.Unreadable -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "CANNOT READ FILE",
                            style = MaterialTheme.typography.titleMedium,
                            color = ComicBlack
                        )
                        Text(
                            text = loadResult.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = ComicMutedInk,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is TextFileLoadResult.Success -> {
                    if (loadResult.isMarkdown) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .testTag("markdown_lazy_column"),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                count = loadResult.markdownBlocks.size,
                                key = { index -> index }
                            ) { index ->
                                ComposeMarkdownBlockItem(block = loadResult.markdownBlocks[index])
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                                .testTag("plain_text_scroll_box")
                        ) {
                            Text(
                                text = loadResult.rawContent,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                ),
                                color = ComicBlack,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun syncStateToPinnedWidgets(
    context: android.content.Context,
    mode: UnifiedWidgetMode,
    currentPath: String,
    selectedImagePath: String?,
    selectedTextPath: String?,
    scaleMode: WidgetImageScaleMode
) {
    val manager = GlanceAppWidgetManager(context)
    val glanceIds = manager.getGlanceIds(UnifiedComicWidget::class.java)
    glanceIds.forEach { glanceId ->
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.WIDGET_MODE] = mode.storageKey
                this[WidgetPreferenceKeys.CURRENT_PATH] = currentPath
                if (!selectedImagePath.isNullOrBlank()) {
                    this[WidgetPreferenceKeys.SELECTED_IMAGE_PATH] = selectedImagePath
                }
                if (!selectedTextPath.isNullOrBlank()) {
                    this[WidgetPreferenceKeys.SELECTED_TEXT_PATH] = selectedTextPath
                }
                this[WidgetPreferenceKeys.IMAGE_SCALE_MODE] = scaleMode.storageKey
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
            }
        }
        UnifiedComicWidget().update(context, glanceId)
    }
}

private fun requestPinUnifiedComicWidget(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, UnifiedComicWidgetReceiver::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val requested = appWidgetManager.requestPinAppWidget(provider, null, null)
            if (!requested) {
                Toast.makeText(
                    context,
                    "Long-press your Home Screen -> Widgets -> Glance Files",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(
                context,
                "Long-press your Home Screen -> Widgets -> Glance Files",
                Toast.LENGTH_SHORT
            ).show()
        }
    } else {
        Toast.makeText(
            context,
            "Long-press your Home Screen -> Widgets -> Glance Files",
            Toast.LENGTH_SHORT
        ).show()
    }
}
