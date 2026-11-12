package com.example

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.io.File

/**
 * The operating modes of [UnifiedComicWidget], persisted per `appWidgetId` in
 * [PreferencesGlanceStateDefinition]:
 * - [BROWSER]: Displays the scrollable Comic Panel folder and file list.
 * - [IMAGE_VIEWER]: Displays a selected image directly from its device file path with
 *   Back icon, 2x Zoom-In / 2x Zoom-Out icon, and tap-for-fullscreen controls.
 * - [TEXT_VIEWER]: Displays a selected `.txt`, code (`.java`, `.c`, etc.), or styled `.md` file
 *   inside a scrollable Comic Panel viewer (with a 1 MB limit and File Picker fallback above 1 MB).
 */
enum class UnifiedWidgetMode(val storageKey: String) {
    BROWSER("BROWSER"),
    IMAGE_VIEWER("IMAGE_VIEWER"),
    TEXT_VIEWER("TEXT_VIEWER");

    companion object {
        fun fromKey(key: String?): UnifiedWidgetMode {
            return entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) }
                ?: BROWSER
        }
    }
}

/**
 * 3-step Zoom-In (+10% per click) / 3-step Zoom-Out (-10% per click) state machine inside
 * [UnifiedWidgetMode.IMAGE_VIEWER]:
 * - [ZOOM_0_IN]: Base 1.00x view (0% zoom). Shows Zoom-In (`+`) icon.
 * - [ZOOM_1_IN]: 1st Zoom-In (1.10x, +10%). Shows Zoom-In (`+`) icon.
 * - [ZOOM_2_IN]: 2nd Zoom-In (1.20x, +20%). Shows Zoom-In (`+`) icon.
 * - [ZOOM_3_OUT]: 3rd Zoom-In (1.30x, +30%). All 3 zoom-ins done -> swaps icon to Zoom-Out (`-`).
 * - [ZOOM_2_OUT]: 1st Zoom-Out (1.20x, +20%). Shows Zoom-Out (`-`) icon.
 * - [ZOOM_1_OUT]: 2nd Zoom-Out (1.10x, +10%). Shows Zoom-Out (`-`) icon.
 *   (3rd zoom-out returns to [ZOOM_0_IN] at 1.00x and swaps icon back to Zoom-In `+`).
 */
enum class WidgetImageScaleMode(
    val storageKey: String,
    val zoomStep: Int,
    val zoomFactor: Float,
    val showZoomOutIcon: Boolean
) {
    ZOOM_0_IN(storageKey = "ZOOM_0_IN", zoomStep = 0, zoomFactor = 1.00f, showZoomOutIcon = false),
    ZOOM_1_IN(storageKey = "ZOOM_1_IN", zoomStep = 1, zoomFactor = 1.10f, showZoomOutIcon = false),
    ZOOM_2_IN(storageKey = "ZOOM_2_IN", zoomStep = 2, zoomFactor = 1.20f, showZoomOutIcon = false),
    ZOOM_3_OUT(storageKey = "ZOOM_3_OUT", zoomStep = 3, zoomFactor = 1.30f, showZoomOutIcon = true),
    ZOOM_2_OUT(storageKey = "ZOOM_2_OUT", zoomStep = 2, zoomFactor = 1.20f, showZoomOutIcon = true),
    ZOOM_1_OUT(storageKey = "ZOOM_1_OUT", zoomStep = 1, zoomFactor = 1.10f, showZoomOutIcon = true);

    fun toggled(): WidgetImageScaleMode = when (this) {
        ZOOM_0_IN -> ZOOM_1_IN
        ZOOM_1_IN -> ZOOM_2_IN
        ZOOM_2_IN -> ZOOM_3_OUT
        ZOOM_3_OUT -> ZOOM_2_OUT
        ZOOM_2_OUT -> ZOOM_1_OUT
        ZOOM_1_OUT -> ZOOM_0_IN
    }

    companion object {
        fun fromKey(key: String?): WidgetImageScaleMode {
            return entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) }
                ?: ZOOM_0_IN
        }
    }
}

/**
 * DataStore Preference keys used by [UnifiedComicWidget] with [PreferencesGlanceStateDefinition].
 */
object WidgetPreferenceKeys {
    val WIDGET_MODE = stringPreferencesKey("widget_mode")
    val CURRENT_PATH = stringPreferencesKey("current_path")
    val SELECTED_IMAGE_PATH = stringPreferencesKey("selected_image_path")
    val SELECTED_TEXT_PATH = stringPreferencesKey("selected_text_path")
    val IMAGE_SCALE_MODE = stringPreferencesKey("image_scale_mode")
    val REFRESH_TIMESTAMP = longPreferencesKey("refresh_timestamp")
    val LAST_STATUS_MESSAGE = stringPreferencesKey("last_status_message")
}

/**
 * ActionParameter keys passed into [ActionCallback] invocations from widget click handlers.
 */
object WidgetActionParams {
    val TARGET_PATH = ActionParameters.Key<String>("target_path")
}

/**
 * Navigates into a tapped subfolder (`current_path`) in `BROWSER` mode.
 */
class EnterFolderAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val targetPath = parameters[WidgetActionParams.TARGET_PATH] ?: return
        val normalizedTarget = FileHelper.normalizePath(targetPath)

        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.WIDGET_MODE] = UnifiedWidgetMode.BROWSER.storageKey
                this[WidgetPreferenceKeys.CURRENT_PATH] = normalizedTarget
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Navigates up to the parent directory when in `BROWSER` mode.
 */
class NavigateUpAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            val currentPath = prefs[WidgetPreferenceKeys.CURRENT_PATH]
                ?: FileHelper.getRootStoragePath()
            val parentPath = FileHelper.getParentPath(currentPath)

            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.WIDGET_MODE] = UnifiedWidgetMode.BROWSER.storageKey
                this[WidgetPreferenceKeys.CURRENT_PATH] = parentPath
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Jumps straight back to the root storage directory (`/storage/emulated/0`) in `BROWSER` mode.
 */
class NavigateRootAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val rootPath = FileHelper.getRootStoragePath()

        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.WIDGET_MODE] = UnifiedWidgetMode.BROWSER.storageKey
                this[WidgetPreferenceKeys.CURRENT_PATH] = rootPath
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Reloads the current directory contents in `BROWSER` mode.
 */
class RefreshDirectoryAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Triggered when tapping an image file (`.jpg`, `.jpeg`, `.png`, `.webp`) in `BROWSER` mode.
 * Switches the widget to `IMAGE_VIEWER` mode and renders the image directly from its file path.
 */
class OpenImageInWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val imagePath = parameters[WidgetActionParams.TARGET_PATH] ?: return

        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.WIDGET_MODE] = UnifiedWidgetMode.IMAGE_VIEWER.storageKey
                this[WidgetPreferenceKeys.SELECTED_IMAGE_PATH] = imagePath
                this[WidgetPreferenceKeys.IMAGE_SCALE_MODE] = WidgetImageScaleMode.ZOOM_0_IN.storageKey
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Triggered by the `← Back` icon button inside `IMAGE_VIEWER` mode.
 * Immediately returns the widget to `BROWSER` mode in the exact folder (`current_path`) the user was viewing.
 */
class ReturnToBrowserModeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.WIDGET_MODE] = UnifiedWidgetMode.BROWSER.storageKey
                this[WidgetPreferenceKeys.IMAGE_SCALE_MODE] = WidgetImageScaleMode.ZOOM_0_IN.storageKey
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Triggered by the Zoom-In / Zoom-Out icon button inside `IMAGE_VIEWER` mode.
 * Advances through the 2-step zoom-in (`ZOOM_0_IN` -> `ZOOM_1_IN` -> `ZOOM_2_OUT`) and
 * 2-step zoom-out (`ZOOM_2_OUT` -> `ZOOM_1_OUT` -> `ZOOM_0_IN`) cycle.
 */
class ToggleImageScaleModeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            val currentMode = WidgetImageScaleMode.fromKey(
                prefs[WidgetPreferenceKeys.IMAGE_SCALE_MODE]
            )
            prefs.toMutablePreferences().apply {
                this[WidgetPreferenceKeys.IMAGE_SCALE_MODE] = currentMode.toggled().storageKey
                this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Triggered when the user taps the displayed image inside `IMAGE_VIEWER` mode.
 * Opens [FullscreenImageActivity] supporting full pinch-to-zoom and panning.
 */
class LaunchFullscreenImageAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val imagePath = parameters[WidgetActionParams.TARGET_PATH] ?: return
        val fullscreenIntent = Intent(context, FullscreenImageActivity::class.java).apply {
            putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, imagePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(fullscreenIntent)
    }
}

/**
 * Triggered when tapping a non-image file in `BROWSER` mode.
 * - Determines if the clicked file is a valid text or `.md` file via [FileHelper.isValidTextFile].
 * - If valid text/markdown, switches the widget to [UnifiedWidgetMode.TEXT_VIEWER] (which enforces
 *   the 1 MB preview limit and offers the file picker if > 1 MB).
 * - Otherwise (binary file), opens the file externally using `FileProvider` and `Intent.ACTION_VIEW`.
 */
class OpenFileAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val filePath = parameters[WidgetActionParams.TARGET_PATH] ?: return
        val targetFile = File(filePath)

        if (FileHelper.isValidTextFile(targetFile)) {
            updateAppWidgetState(
                context = context,
                definition = PreferencesGlanceStateDefinition,
                glanceId = glanceId
            ) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[WidgetPreferenceKeys.WIDGET_MODE] = UnifiedWidgetMode.TEXT_VIEWER.storageKey
                    this[WidgetPreferenceKeys.SELECTED_TEXT_PATH] = targetFile.absolutePath
                    this[WidgetPreferenceKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
                    this.remove(WidgetPreferenceKeys.LAST_STATUS_MESSAGE)
                }
            }
            UnifiedComicWidget().update(context, glanceId)
            return
        }

        val openResult = FileHelper.openFileExternally(context, targetFile)

        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                openResult.fold(
                    onSuccess = { msg ->
                        this[WidgetPreferenceKeys.LAST_STATUS_MESSAGE] = msg
                    },
                    onFailure = { err ->
                        this[WidgetPreferenceKeys.LAST_STATUS_MESSAGE] =
                            err.localizedMessage ?: "No app found to open ${targetFile.name}"
                    }
                )
            }
        }

        UnifiedComicWidget().update(context, glanceId)
    }
}

/**
 * Triggered when a text file exceeds the 1 MB preview limit (or when the user taps
 * "Open with File Picker") to launch Android's app chooser via `FileProvider`.
 */
class LaunchFilePickerAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val filePath = parameters[WidgetActionParams.TARGET_PATH] ?: return
        val targetFile = File(filePath)
        FileHelper.openFileWithPicker(context, targetFile)
    }
}

/**
 * Opens system storage access settings (`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`)
 * when the user taps "Grant Storage Access" inside the widget.
 */
class GrantStoragePermissionAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        FileHelper.launchStoragePermissionSettings(context)
        UnifiedComicWidget().updateAll(context)
    }
}
