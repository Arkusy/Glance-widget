package com.example

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.io.File

/**
 * BroadcastReceiver registered in `AndroidManifest.xml` for the Unified Comic Panel Widget.
 */
class UnifiedComicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UnifiedComicWidget()
}

/**
 * Unified Comic Panel "File Browser & Image Viewer" Home Screen Widget built with Jetpack Glance
 * and [PreferencesGlanceStateDefinition].
 *
 * Modes:
 * 1. `BROWSER` (Default): Displays the Comic Panel file and folder list. Tapping a folder enters it;
 *    tapping an image file (`.jpg`, `.jpeg`, `.png`, `.webp`) switches the widget to `IMAGE_VIEWER` mode;
 *    tapping any other file opens it externally via `FileProvider`.
 * 2. `IMAGE_VIEWER`: Displays the selected image directly from its device file path (downsampled
 *    safely to max `1080px`) inside a `2.dp` sharp black Comic Panel frame with `← Back`,
 *    `Fit / Crop` toggle, and tap-to-open [FullscreenImageActivity].
 */
class UnifiedComicWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                UnifiedComicWidgetRoot(context = context)
            }
        }
    }
}

// Comic Panel Monochrome ColorProviders
private val ComicWhiteProvider = ColorProvider(Color(0xFFFFFFFF))
private val ComicBlackProvider = ColorProvider(Color(0xFF000000))
private val ComicPanelGrayProvider = ColorProvider(Color(0xFFF4F4F5))
private val ComicMutedInkProvider = ColorProvider(Color(0xFF52525B))
private val ComicDisabledInkProvider = ColorProvider(Color(0xFFD4D4D8))

@Composable
private fun UnifiedComicWidgetRoot(context: Context) {
    val prefs = currentState<Preferences>()
    val mode = UnifiedWidgetMode.fromKey(prefs[WidgetPreferenceKeys.WIDGET_MODE])
    val savedPath = prefs[WidgetPreferenceKeys.CURRENT_PATH]
    val selectedImagePath = prefs[WidgetPreferenceKeys.SELECTED_IMAGE_PATH]
    val selectedTextPath = prefs[WidgetPreferenceKeys.SELECTED_TEXT_PATH]
    val selectedPdfPath = prefs[WidgetPreferenceKeys.SELECTED_PDF_PATH]
    val pdfPageOffset = prefs[WidgetPreferenceKeys.PDF_PAGE_OFFSET] ?: 0
    val scaleMode = WidgetImageScaleMode.fromKey(prefs[WidgetPreferenceKeys.IMAGE_SCALE_MODE])
    val statusMessage = prefs[WidgetPreferenceKeys.LAST_STATUS_MESSAGE]
    val refreshTimestamp = prefs[WidgetPreferenceKeys.REFRESH_TIMESTAMP] ?: 0L

    val currentPath = FileHelper.normalizePath(savedPath)

    // Outer 2.dp Sharp Black Comic Panel Frame
    ComicPanelBox(
        modifier = GlanceModifier.fillMaxSize()
    ) {
        if (!FileHelper.hasStoragePermission(context)) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ComicWhiteProvider)
                    .padding(10.dp)
            ) {
                PermissionFallbackPanel()
            }
        } else if (mode == UnifiedWidgetMode.IMAGE_VIEWER && !selectedImagePath.isNullOrBlank()) {
            ComicImageViewerModeContent(
                context = context,
                imagePath = selectedImagePath,
                scaleMode = scaleMode
            )
        } else if (mode == UnifiedWidgetMode.PDF_VIEWER && !selectedPdfPath.isNullOrBlank()) {
            ComicPdfViewerModeContent(
                context = context,
                pdfPath = selectedPdfPath,
                pageOffset = pdfPageOffset
            )
        } else if (mode == UnifiedWidgetMode.TEXT_VIEWER && !selectedTextPath.isNullOrBlank()) {
            ComicTextViewerModeContent(
                context = context,
                textPath = selectedTextPath
            )
        } else {
            ComicBrowserModeContent(
                context = context,
                currentPath = currentPath,
                statusMessage = statusMessage,
                refreshTimestamp = refreshTimestamp
            )
        }
    }
}

/**
 * Renders a crisp `2.dp` solid black Comic Panel border (`#000000`) around a pure white (`#FFFFFF`) interior.
 */
@Composable
private fun ComicPanelBox(
    modifier: GlanceModifier = GlanceModifier,
    onClickAction: Action? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(ComicBlackProvider)
            .cornerRadius(2.dp)
            .let { mod ->
                if (onClickAction != null) mod.clickable(onClickAction) else mod
            }
            .padding(2.dp)
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ComicWhiteProvider)
                .cornerRadius(2.dp)
        ) {
            content()
        }
    }
}

/**
 * Mode 1: `BROWSER` Mode — Comic Panel File & Folder List.
 */
@Composable
private fun ComicBrowserModeContent(
    context: Context,
    currentPath: String,
    statusMessage: String?,
    refreshTimestamp: Long
) {
    val isAtRoot = FileHelper.isAtRootDirectory(currentPath)
    val loadResult = FileHelper.listDirectoryContents(
        context = context,
        targetPath = currentPath
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(8.dp)
    ) {
        // Top Header Bar
        BrowserHeaderBar(
            context = context,
            currentPath = currentPath,
            isAtRoot = isAtRoot,
            refreshTimestamp = refreshTimestamp
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        if (!statusMessage.isNullOrBlank()) {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ComicBlackProvider)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusMessage.uppercase(),
                    style = TextStyle(
                        color = ComicWhiteProvider,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1
                )
            }
            Spacer(modifier = GlanceModifier.height(6.dp))
        }

        when (loadResult) {
            is DirectoryLoadResult.PermissionRequired -> {
                PermissionFallbackPanel()
            }

            is DirectoryLoadResult.Unreadable -> {
                UnreadableDirectoryPanel(
                    reason = loadResult.reason
                )
            }

            is DirectoryLoadResult.Empty -> {
                EmptyDirectoryPanel(
                    folderName = loadResult.displayFolderName,
                    isAtRoot = isAtRoot
                )
            }

            is DirectoryLoadResult.Success -> {
                LazyColumn(
                    modifier = GlanceModifier.fillMaxSize()
                ) {
                    items(
                        items = loadResult.items,
                        itemId = { item -> item.absolutePath.hashCode().toLong() }
                    ) { item ->
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            ComicFileRowItem(item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserHeaderBar(
    context: Context,
    currentPath: String,
    isAtRoot: Boolean,
    refreshTimestamp: Long
) {
    val folderTitle = FileHelper.getDisplayFolderName(currentPath)
    val truncatedPath = FileHelper.getTruncatedDisplayPath(currentPath)
    val headerBitmap = FileHelper.renderComicHeaderBitmap(context, folderTitle)

    ComicPanelBox(
        modifier = GlanceModifier.fillMaxWidth().height(50.dp)
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ComicPanelGrayProvider)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back / Up Button
            ComicIconButton(
                iconRes = R.drawable.ic_comic_back,
                contentDescription = "Up to parent folder",
                enabled = !isAtRoot,
                onClick = if (!isAtRoot) actionRunCallback<NavigateUpAction>() else null
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            Column(
                modifier = GlanceModifier.defaultWeight()
            ) {
                Image(
                    provider = ImageProvider(headerBitmap),
                    contentDescription = folderTitle,
                    modifier = GlanceModifier.height(19.dp)
                )
                Text(
                    text = truncatedPath,
                    style = TextStyle(
                        color = ComicMutedInkProvider,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = GlanceModifier.width(4.dp))

            // Home / Root Button
            ComicIconButton(
                iconRes = R.drawable.ic_comic_home,
                contentDescription = "Root folder",
                enabled = true,
                onClick = actionRunCallback<NavigateRootAction>()
            )

            Spacer(modifier = GlanceModifier.width(4.dp))

            // Refresh Button
            ComicIconButton(
                iconRes = R.drawable.ic_comic_refresh,
                contentDescription = if (refreshTimestamp > 0L) "Refresh folder" else "Refresh",
                enabled = true,
                onClick = actionRunCallback<RefreshDirectoryAction>()
            )
        }
    }
}

@Composable
private fun ComicIconButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: Action?
) {
    Box(
        modifier = GlanceModifier
            .size(34.dp)
            .background(if (enabled) ComicBlackProvider else ComicDisabledInkProvider)
            .cornerRadius(2.dp)
            .let { mod ->
                if (enabled && onClick != null) mod.clickable(onClick) else mod
            }
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ComicWhiteProvider),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(18.dp),
                colorFilter = if (enabled) null else ColorFilter.tint(ComicDisabledInkProvider)
            )
        }
    }
}

@Composable
private fun ComicFileRowItem(item: FileBrowserItem) {
    val clickAction = when {
        item.isDirectory -> actionRunCallback<EnterFolderAction>(
            actionParametersOf(WidgetActionParams.TARGET_PATH to item.absolutePath)
        )
        item.isImage -> actionRunCallback<OpenImageInWidgetAction>(
            actionParametersOf(WidgetActionParams.TARGET_PATH to item.absolutePath)
        )
        item.isPdf -> actionRunCallback<OpenPdfInWidgetAction>(
            actionParametersOf(WidgetActionParams.TARGET_PATH to item.absolutePath)
        )
        else -> actionRunCallback<OpenFileAction>(
            actionParametersOf(WidgetActionParams.TARGET_PATH to item.absolutePath)
        )
    }

    ComicPanelBox(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp),
        onClickAction = clickAction
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ComicWhiteProvider)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(item.category.iconRes),
                contentDescription = item.category.badgeLabel,
                modifier = GlanceModifier.size(24.dp)
            )

            Spacer(modifier = GlanceModifier.width(10.dp))

            Column(
                modifier = GlanceModifier.defaultWeight()
            ) {
                Text(
                    text = if (item.isDirectory) item.name.uppercase() else item.name,
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Text(
                    text = item.formattedSubtitle,
                    style = TextStyle(
                        color = ComicMutedInkProvider,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = GlanceModifier.width(6.dp))

            if (item.isDirectory) {
                Image(
                    provider = ImageProvider(R.drawable.ic_chevron_right),
                    contentDescription = "Open folder",
                    modifier = GlanceModifier.size(18.dp)
                )
            } else {
                val badgeText = FileHelper.getFileFormatBadge(File(item.absolutePath))
                Box(
                    modifier = GlanceModifier
                        .background(ComicBlackProvider)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = TextStyle(
                            color = ComicWhiteProvider,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

/**
 * Mode 2: `IMAGE_VIEWER` Mode — Displays the selected image directly from its file path
 * inside the `2.dp` black Comic Panel frame with:
 * - Left: Back icon button (`ic_comic_back`) to return to `BROWSER` mode in the same folder.
 * - Center: Image filename on top + folder path below (matching the `BROWSER` mode header).
 * - Right: 2-step Zoom-In (`ic_comic_zoom_in`) / 2-step Zoom-Out (`ic_comic_zoom_out`) icon button.
 * - Tapping the image opens [FullscreenImageActivity].
 */
@Composable
private fun ComicImageViewerModeContent(
    context: Context,
    imagePath: String,
    scaleMode: WidgetImageScaleMode
) {
    val file = File(imagePath)
    val parentFolderPath = file.parentFile?.absolutePath ?: FileHelper.getRootStoragePath()
    val truncatedPath = FileHelper.getTruncatedDisplayPath(parentFolderPath)
    val widgetSize = LocalSize.current
    val frameWidthDp = (widgetSize.width.value - 20f).coerceAtLeast(80f)
    val frameHeightDp = (widgetSize.height.value - 76f).coerceAtLeast(80f)
    val containerAspect = if (widgetSize.width.value > 0f && widgetSize.height.value > 0f) {
        (frameWidthDp / frameHeightDp).coerceIn(0.25f, 4.0f)
    } else {
        1.0f
    }
    val bitmap = FileHelper.loadZoomedBitmap(
        filePath = imagePath,
        zoomFactor = scaleMode.zoomFactor,
        containerAspect = containerAspect
    )
    val titleBitmap = FileHelper.renderComicHeaderBitmap(
        context = context,
        title = file.name.ifBlank { "IMAGE" },
        uppercase = false
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(8.dp)
    ) {
        // Top Image Viewer Header Bar: [Back Icon] | Image Name + Path | [Zoom In / Zoom Out Icon]
        ComicPanelBox(
            modifier = GlanceModifier.fillMaxWidth().height(50.dp)
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ComicPanelGrayProvider)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Icon Button (returns immediately to BROWSER mode in the exact same folder)
                ComicIconButton(
                    iconRes = R.drawable.ic_comic_back,
                    contentDescription = "Back to folder",
                    enabled = true,
                    onClick = actionRunCallback<ReturnToBrowserModeAction>()
                )

                Spacer(modifier = GlanceModifier.width(8.dp))

                Column(
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    Image(
                        provider = ImageProvider(titleBitmap),
                        contentDescription = file.name,
                        modifier = GlanceModifier.height(19.dp)
                    )
                    Text(
                        text = truncatedPath,
                        style = TextStyle(
                            color = ComicMutedInkProvider,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = GlanceModifier.width(6.dp))

                // Zoom In (2x) / Zoom Out (2x) Icon Button
                ComicIconButton(
                    iconRes = if (scaleMode.showZoomOutIcon) {
                        R.drawable.ic_comic_zoom_out
                    } else {
                        R.drawable.ic_comic_zoom_in
                    },
                    contentDescription = if (scaleMode.showZoomOutIcon) "Zoom out" else "Zoom in",
                    enabled = true,
                    onClick = actionRunCallback<ToggleImageScaleModeAction>()
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        // Main Comic Panel Image Frame (Tapping the image opens FullscreenImageActivity)
        if (bitmap != null) {
            ComicPanelBox(
                modifier = GlanceModifier.fillMaxSize(),
                onClickAction = actionRunCallback<LaunchFullscreenImageAction>(
                    actionParametersOf(WidgetActionParams.TARGET_PATH to imagePath)
                )
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ComicWhiteProvider),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.fillMaxSize()
                    )
                }
            }
        } else {
            ComicPanelBox(
                modifier = GlanceModifier.fillMaxSize()
            ) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ComicWhiteProvider)
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "IMAGE UNAVAILABLE",
                        style = TextStyle(
                            color = ComicBlackProvider,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    ComicIconButton(
                        iconRes = R.drawable.ic_comic_back,
                        contentDescription = "Back to files",
                        enabled = true,
                        onClick = actionRunCallback<ReturnToBrowserModeAction>()
                    )
                }
            }
        }
    }
}

/**
 * Mode 3: `TEXT_VIEWER` Mode — Displays `.txt`, code files (`.java`, `.c`, `.kt`, `.py`, etc.),
 * or styled `.md` Markdown files in a scrollable vertical list (`LazyColumn`).
 * Enforces a `1 MB` preview limit and provides an "Open with File Picker" button if exceeded.
 */
@Composable
private fun ComicTextViewerModeContent(
    context: Context,
    textPath: String
) {
    val file = File(textPath)
    val parentFolderPath = file.parentFile?.absolutePath ?: FileHelper.getRootStoragePath()
    val truncatedPath = FileHelper.getTruncatedDisplayPath(parentFolderPath)
    val isMarkdown = FileHelper.isMarkdownFile(file)
    val titleBitmap = FileHelper.renderComicHeaderBitmap(
        context = context,
        title = file.name.ifBlank { "DOCUMENT" },
        uppercase = false
    )
    val loadResult = FileHelper.loadTextFileForPreview(textPath)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(8.dp)
    ) {
        // Top Header Bar: [Back Icon] | File Name + Path | [MD / TXT Mode Badge]
        ComicPanelBox(
            modifier = GlanceModifier.fillMaxWidth().height(50.dp)
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ComicPanelGrayProvider)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ComicIconButton(
                    iconRes = R.drawable.ic_comic_back,
                    contentDescription = "Back to folder",
                    enabled = true,
                    onClick = actionRunCallback<ReturnToBrowserModeAction>()
                )

                Spacer(modifier = GlanceModifier.width(8.dp))

                Column(
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    Image(
                        provider = ImageProvider(titleBitmap),
                        contentDescription = file.name,
                        modifier = GlanceModifier.height(19.dp)
                    )
                    Text(
                        text = truncatedPath,
                        style = TextStyle(
                            color = ComicMutedInkProvider,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = GlanceModifier.width(6.dp))

                Box(
                    modifier = GlanceModifier
                        .background(ComicBlackProvider)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = FileHelper.getFileFormatBadge(file),
                        style = TextStyle(
                            color = ComicWhiteProvider,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        // Main Scrollable Text / Markdown Content Frame
        ComicPanelBox(
            modifier = GlanceModifier.fillMaxSize()
        ) {
            when (loadResult) {
                is TextFileLoadResult.TooLargeForPreview -> {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ComicWhiteProvider)
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FILE EXCEEDS 1 MB LIMIT",
                            style = TextStyle(
                                color = ComicBlackProvider,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = "${loadResult.fileName} (${loadResult.formattedSize} > 1.0 MB)",
                            style = TextStyle(
                                color = ComicMutedInkProvider,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 2
                        )
                        Spacer(modifier = GlanceModifier.height(12.dp))
                        Box(
                            modifier = GlanceModifier
                                .background(ComicBlackProvider)
                                .cornerRadius(2.dp)
                                .clickable(
                                    actionRunCallback<LaunchFilePickerAction>(
                                        actionParametersOf(WidgetActionParams.TARGET_PATH to textPath)
                                    )
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "OPEN WITH FILE PICKER",
                                style = TextStyle(
                                    color = ComicWhiteProvider,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }

                is TextFileLoadResult.Unreadable -> {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ComicWhiteProvider)
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CANNOT READ FILE",
                            style = TextStyle(
                                color = ComicBlackProvider,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = loadResult.reason,
                            style = TextStyle(
                                color = ComicMutedInkProvider,
                                fontSize = 11.sp
                            ),
                            maxLines = 2
                        )
                    }
                }

                is TextFileLoadResult.Success -> {
                    if (loadResult.isMarkdown) {
                        val indexedBlocks = loadResult.markdownBlocks.withIndex().toList()
                        LazyColumn(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(ComicWhiteProvider)
                                .padding(8.dp)
                        ) {
                            items(
                                items = indexedBlocks,
                                itemId = { it.index.toLong() }
                            ) { indexed ->
                                Column(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                ) {
                                    GlanceMarkdownBlockItem(block = indexed.value)
                                }
                            }
                        }
                    } else {
                        val indexedChunks = loadResult.linesOrChunks.withIndex().toList()
                        LazyColumn(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(ComicWhiteProvider)
                                .padding(8.dp)
                        ) {
                            items(
                                items = indexedChunks,
                                itemId = { it.index.toLong() }
                            ) { chunk ->
                                Text(
                                    text = chunk.value,
                                    style = TextStyle(
                                        color = ComicBlackProvider,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mode 4: `PDF_VIEWER` Mode — Displays `.pdf` files in a scrollable vertical list (`LazyColumn`)
 * of rendered PDF pages using Android's `PdfRenderer`.
 * Enforces a `10 MB` preview limit and provides an "Open with File Picker" button if exceeded.
 */
@Composable
private fun ComicPdfViewerModeContent(
    context: Context,
    pdfPath: String,
    pageOffset: Int
) {
    val file = File(pdfPath)
    val parentFolderPath = file.parentFile?.absolutePath ?: FileHelper.getRootStoragePath()
    val truncatedPath = FileHelper.getTruncatedDisplayPath(parentFolderPath)
    val titleBitmap = FileHelper.renderComicHeaderBitmap(
        context = context,
        title = file.name.ifBlank { "DOCUMENT.PDF" },
        uppercase = false
    )
    val loadResult = FileHelper.loadPdfForPreview(
        filePath = pdfPath,
        startPage = pageOffset,
        maxPages = FileHelper.MAX_GLANCE_PDF_PAGES_PER_BATCH,
        targetWidthPx = 540
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(8.dp)
    ) {
        // Top Header Bar: [Back Icon] | PDF Name + Path | [PDF Badge]
        ComicPanelBox(
            modifier = GlanceModifier.fillMaxWidth().height(50.dp)
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ComicPanelGrayProvider)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ComicIconButton(
                    iconRes = R.drawable.ic_comic_back,
                    contentDescription = "Back to folder",
                    enabled = true,
                    onClick = actionRunCallback<ReturnToBrowserModeAction>()
                )

                Spacer(modifier = GlanceModifier.width(8.dp))

                Column(
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    Image(
                        provider = ImageProvider(titleBitmap),
                        contentDescription = file.name,
                        modifier = GlanceModifier.height(19.dp)
                    )
                    Text(
                        text = truncatedPath,
                        style = TextStyle(
                            color = ComicMutedInkProvider,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = GlanceModifier.width(6.dp))

                Box(
                    modifier = GlanceModifier
                        .background(ComicBlackProvider)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = FileHelper.getFileFormatBadge(file),
                        style = TextStyle(
                            color = ComicWhiteProvider,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        // Main Scrollable PDF Pages Frame
        ComicPanelBox(
            modifier = GlanceModifier.fillMaxSize()
        ) {
            when (loadResult) {
                is PdfFileLoadResult.TooLargeForPreview -> {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ComicWhiteProvider)
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FILE EXCEEDS 10 MB LIMIT",
                            style = TextStyle(
                                color = ComicBlackProvider,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = "${loadResult.fileName} (${loadResult.formattedSize} > 10.0 MB)",
                            style = TextStyle(
                                color = ComicMutedInkProvider,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 2
                        )
                        Spacer(modifier = GlanceModifier.height(12.dp))
                        Box(
                            modifier = GlanceModifier
                                .background(ComicBlackProvider)
                                .cornerRadius(2.dp)
                                .clickable(
                                    actionRunCallback<LaunchFilePickerAction>(
                                        actionParametersOf(WidgetActionParams.TARGET_PATH to pdfPath)
                                    )
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "OPEN WITH FILE PICKER",
                                style = TextStyle(
                                    color = ComicWhiteProvider,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }

                is PdfFileLoadResult.Unreadable -> {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ComicWhiteProvider)
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CANNOT READ PDF",
                            style = TextStyle(
                                color = ComicBlackProvider,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = loadResult.reason,
                            style = TextStyle(
                                color = ComicMutedInkProvider,
                                fontSize = 11.sp
                            ),
                            maxLines = 2
                        )
                    }
                }

                is PdfFileLoadResult.Success -> {
                    val widgetSize = LocalSize.current
                    val pageWidthDp = (widgetSize.width.value - 36f).coerceAtLeast(180f)
                    val hasMoreBatches = loadResult.totalPages > FileHelper.MAX_GLANCE_PDF_PAGES_PER_BATCH
                    val canPrevBatch = loadResult.startPageIndex > 0
                    val canNextBatch = loadResult.endPageExclusive < loadResult.totalPages

                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ComicWhiteProvider)
                    ) {
                        if (hasMoreBatches) {
                            Row(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .background(ComicPanelGrayProvider)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = GlanceModifier
                                        .background(if (canPrevBatch) ComicBlackProvider else ComicDisabledInkProvider)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .let { mod ->
                                            if (canPrevBatch) {
                                                mod.clickable(
                                                    actionRunCallback<ChangePdfPageBatchAction>(
                                                        actionParametersOf(
                                                            WidgetActionParams.PAGE_OFFSET to
                                                                (loadResult.startPageIndex - FileHelper.MAX_GLANCE_PDF_PAGES_PER_BATCH).coerceAtLeast(0)
                                                        )
                                                    )
                                                )
                                            } else {
                                                mod
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "← PREV",
                                        style = TextStyle(
                                            color = ComicWhiteProvider,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }

                                Text(
                                    text = "PAGES ${loadResult.startPageIndex + 1}–${loadResult.endPageExclusive} OF ${loadResult.totalPages}",
                                    style = TextStyle(
                                        color = ComicBlackProvider,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 6.dp)
                                )

                                Box(
                                    modifier = GlanceModifier
                                        .background(if (canNextBatch) ComicBlackProvider else ComicDisabledInkProvider)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .let { mod ->
                                            if (canNextBatch) {
                                                mod.clickable(
                                                    actionRunCallback<ChangePdfPageBatchAction>(
                                                        actionParametersOf(
                                                            WidgetActionParams.PAGE_OFFSET to loadResult.endPageExclusive
                                                        )
                                                    )
                                                )
                                            } else {
                                                mod
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "NEXT →",
                                        style = TextStyle(
                                            color = ComicWhiteProvider,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(ComicWhiteProvider)
                                .padding(6.dp)
                        ) {
                            items(
                                items = loadResult.pages,
                                itemId = { it.pageNumber.toLong() }
                            ) { renderedPage ->
                                val bmpAspect = renderedPage.bitmap.height.toFloat() /
                                    renderedPage.bitmap.width.toFloat().coerceAtLeast(1f)
                                val pageHeightDp = (pageWidthDp * bmpAspect).coerceIn(140f, 520f).dp

                                Column(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Box(
                                        modifier = GlanceModifier
                                            .fillMaxWidth()
                                            .background(ComicBlackProvider)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "PAGE ${renderedPage.pageNumber} OF ${renderedPage.totalPages}",
                                            style = TextStyle(
                                                color = ComicWhiteProvider,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                    ComicPanelBox(
                                        modifier = GlanceModifier
                                            .fillMaxWidth()
                                            .height(pageHeightDp)
                                    ) {
                                        Image(
                                            provider = ImageProvider(renderedPage.bitmap),
                                            contentDescription = "Page ${renderedPage.pageNumber}",
                                            contentScale = ContentScale.Fit,
                                            modifier = GlanceModifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlanceMarkdownBlockItem(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val fontSize = when (block.level) {
                1 -> 16.sp
                2 -> 14.sp
                3 -> 13.sp
                else -> 12.sp
            }
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = MarkdownHelper.formatInlineMarkdownForGlance(block.text),
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (block.level <= 2) {
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(ComicBlackProvider)
                    ) {}
                }
            }
        }

        is MarkdownBlock.Paragraph -> {
            Text(
                text = MarkdownHelper.formatInlineMarkdownForGlance(block.text),
                style = TextStyle(
                    color = ComicBlackProvider,
                    fontSize = 12.sp
                ),
                modifier = GlanceModifier.fillMaxWidth()
            )
        }

        is MarkdownBlock.CodeBlock -> {
            ComicPanelBox(
                modifier = GlanceModifier.fillMaxWidth()
            ) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(ComicPanelGrayProvider)
                        .padding(6.dp)
                ) {
                    if (block.language.isNotBlank()) {
                        Box(
                            modifier = GlanceModifier
                                .background(ComicBlackProvider)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = block.language.uppercase(),
                                style = TextStyle(
                                    color = ComicWhiteProvider,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }
                    Text(
                        text = block.code,
                        style = TextStyle(
                            color = ComicBlackProvider,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }

        is MarkdownBlock.Blockquote -> {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ComicBlackProvider)
                    .padding(start = 4.dp, top = 1.dp, bottom = 1.dp, end = 1.dp)
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(ComicPanelGrayProvider)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = MarkdownHelper.formatInlineMarkdownForGlance(block.text),
                        style = TextStyle(
                            color = ComicBlackProvider,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic
                        )
                    )
                }
            }
        }

        is MarkdownBlock.BulletListItem -> {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(start = (block.indentLevel * 8).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (block.indentLevel == 0) "• " else "◦ ",
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = MarkdownHelper.formatInlineMarkdownForGlance(block.text),
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = 12.sp
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }

        is MarkdownBlock.OrderedListItem -> {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(start = (block.indentLevel * 8).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${block.numberLabel} ",
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Text(
                    text = MarkdownHelper.formatInlineMarkdownForGlance(block.text),
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = 12.sp
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }

        is MarkdownBlock.TaskListItem -> {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (block.isChecked) "☑ " else "☐ ",
                    style = TextStyle(
                        color = ComicBlackProvider,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = MarkdownHelper.formatInlineMarkdownForGlance(block.text),
                    style = TextStyle(
                        color = if (block.isChecked) ComicMutedInkProvider else ComicBlackProvider,
                        fontSize = 12.sp,
                        textDecoration = if (block.isChecked) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }

        is MarkdownBlock.TableRowBlock -> {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(ComicBlackProvider)
                    .padding(1.dp)
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(
                            if (block.isHeader) ComicPanelGrayProvider else ComicWhiteProvider
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    block.cells.forEach { cellText ->
                        Text(
                            text = MarkdownHelper.formatInlineMarkdownForGlance(cellText),
                            style = TextStyle(
                                color = ComicBlackProvider,
                                fontSize = 10.sp,
                                fontWeight = if (block.isHeader) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                }
            }
        }

        is MarkdownBlock.HorizontalRule -> {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ComicBlackProvider)
            ) {}
        }
    }
}

@Composable
private fun PermissionFallbackPanel() {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_lock_storage),
            contentDescription = "Storage access required",
            modifier = GlanceModifier.size(30.dp)
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        Text(
            text = "STORAGE ACCESS NEEDED",
            style = TextStyle(
                color = ComicBlackProvider,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )

        Spacer(modifier = GlanceModifier.height(10.dp))

        Box(
            modifier = GlanceModifier
                .background(ComicBlackProvider)
                .clickable(actionRunCallback<GrantStoragePermissionAction>())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "GRANT STORAGE ACCESS",
                style = TextStyle(
                    color = ComicWhiteProvider,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
private fun EmptyDirectoryPanel(
    folderName: String,
    isAtRoot: Boolean
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_folder_empty),
            contentDescription = "Empty folder",
            modifier = GlanceModifier.size(28.dp)
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        Text(
            text = "\"${folderName.uppercase()}\" IS EMPTY",
            style = TextStyle(
                color = ComicBlackProvider,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            maxLines = 1
        )

        if (!isAtRoot) {
            Spacer(modifier = GlanceModifier.height(10.dp))
            Box(
                modifier = GlanceModifier
                    .background(ComicBlackProvider)
                    .clickable(actionRunCallback<NavigateUpAction>())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "← BACK UP",
                    style = TextStyle(
                        color = ComicWhiteProvider,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

@Composable
private fun UnreadableDirectoryPanel(reason: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ComicWhiteProvider)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "CANNOT READ FOLDER",
            style = TextStyle(
                color = ComicBlackProvider,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )

        Spacer(modifier = GlanceModifier.height(4.dp))

        Text(
            text = reason,
            style = TextStyle(
                color = ComicMutedInkProvider,
                fontSize = 11.sp
            ),
            maxLines = 2
        )

        Spacer(modifier = GlanceModifier.height(10.dp))

        Box(
            modifier = GlanceModifier
                .background(ComicBlackProvider)
                .clickable(actionRunCallback<NavigateRootAction>())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "RETURN TO ROOT",
                style = TextStyle(
                    color = ComicWhiteProvider,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}
