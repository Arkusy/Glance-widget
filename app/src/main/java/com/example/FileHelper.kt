package com.example

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Monochrome Comic Panel file categories mapped to `ic_comic_*` Vector Drawables.
 */
enum class FileCategory(
    @DrawableRes val iconRes: Int,
    val badgeLabel: String
) {
    FOLDER(R.drawable.ic_comic_folder, "DIR"),
    IMAGE(R.drawable.ic_comic_image, "IMG"),
    VIDEO(R.drawable.ic_comic_video, "VID"),
    AUDIO(R.drawable.ic_comic_audio, "AUD"),
    DOCUMENT(R.drawable.ic_comic_doc, "DOC"),
    ARCHIVE(R.drawable.ic_comic_archive, "ZIP"),
    GENERIC(R.drawable.ic_comic_file, "FILE")
}

/**
 * Lightweight, immutable representation of a file or folder item inside the Comic Panel widget.
 */
data class FileBrowserItem(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val isImage: Boolean,
    val isText: Boolean,
    val isMarkdown: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val childCount: Int,
    val category: FileCategory,
    val formattedSubtitle: String
)

/**
 * Sealed result representing the outcome of loading a text or `.md` file for the in-widget /
 * in-app text viewer (enforcing a strict `1 MB` preview limit).
 */
sealed interface TextFileLoadResult {
    data class Success(
        val fileName: String,
        val fullPath: String,
        val isMarkdown: Boolean,
        val rawContent: String,
        val linesOrChunks: List<String>,
        val markdownBlocks: List<MarkdownBlock>
    ) : TextFileLoadResult

    data class TooLargeForPreview(
        val fileName: String,
        val fullPath: String,
        val fileSizeBytes: Long,
        val formattedSize: String
    ) : TextFileLoadResult

    data class Unreadable(
        val fileName: String,
        val reason: String
    ) : TextFileLoadResult
}

/**
 * Sealed result representing the outcome of reading a directory for the widget UI.
 */
sealed interface DirectoryLoadResult {
    data object PermissionRequired : DirectoryLoadResult

    data class Unreadable(
        val attemptedPath: String,
        val reason: String
    ) : DirectoryLoadResult

    data class Empty(
        val currentPath: String,
        val displayFolderName: String
    ) : DirectoryLoadResult

    data class Success(
        val currentPath: String,
        val displayFolderName: String,
        val items: List<FileBrowserItem>,
        val totalFoldersCount: Int,
        val totalFilesCount: Int,
        val isTruncatedForBinderLimit: Boolean
    ) : DirectoryLoadResult
}

/**
 * Utility functions for storage permission checks, directory listing & sorting,
 * direct file-path image downsampling (max 1080px for Binder safety), valid text/markdown
 * detection with a 1 MB preview limit, and external file launching.
 */
object FileHelper {

    const val MAX_WIDGET_ITEMS = 80
    const val MAX_WIDGET_BITMAP_DIMENSION = 1080
    const val MAX_TEXT_PREVIEW_BYTES = 1_048_576L // 1 MB (1024 * 1024 bytes)
    private const val MAX_GLANCE_TEXT_CHUNKS = 120
    private const val MAX_GLANCE_MARKDOWN_BLOCKS = 140

    private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "bmp"
    )

    private val MARKDOWN_EXTENSIONS = setOf(
        "md", "markdown", "mdown", "mkd"
    )

    private val KNOWN_TEXT_EXTENSIONS = setOf(
        "txt", "text", "md", "markdown", "mdown", "mkd",
        "java", "kt", "kts", "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
        "cs", "py", "rb", "go", "rs", "swift", "dart", "php", "lua", "pl", "r",
        "js", "jsx", "mjs", "cjs", "ts", "tsx",
        "html", "htm", "css", "scss", "sass", "less", "xml", "svg",
        "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
        "gradle", "pro", "mk", "cmake",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        "sql", "csv", "tsv", "log", "gitignore", "gitattributes", "env", "tex", "rst", "adoc"
    )

    private val KNOWN_EXTENSIONLESS_TEXT_NAMES = setOf(
        "readme", "license", "copying", "makefile", "dockerfile",
        "authors", "changelog", "contributing", "notice", "todo", "hosts"
    )

    private val KNOWN_BINARY_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "ico",
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv",
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "wma",
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst",
        "apk", "aab", "dex", "so", "jar", "class", "o", "a", "dll", "exe", "bin", "iso", "img",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "ttf", "otf", "woff", "woff2", "db", "sqlite", "sqlite3"
    )

    /**
     * Checks whether the app holds storage access (`Environment.isExternalStorageManager()` on Android 11+).
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns the canonical root path of primary shared storage (typically `/storage/emulated/0`).
     */
    fun getRootStoragePath(): String {
        return try {
            Environment.getExternalStorageDirectory().canonicalPath
        } catch (_: Exception) {
            Environment.getExternalStorageDirectory().absolutePath
        }
    }

    /**
     * Normalizes an incoming path and falls back to [getRootStoragePath] if blank or invalid.
     */
    fun normalizePath(rawPath: String?): String {
        val rootPath = getRootStoragePath()
        if (rawPath.isNullOrBlank()) return rootPath
        return try {
            val canonical = File(rawPath).canonicalPath
            if (canonical.startsWith(rootPath)) canonical else rootPath
        } catch (_: Exception) {
            rootPath
        }
    }

    /**
     * Determines whether the given [path] is at (or above) the root directory.
     */
    fun isAtRootDirectory(path: String?): Boolean {
        val rootPath = getRootStoragePath()
        if (path.isNullOrBlank()) return true
        val normalized = normalizePath(path).trimEnd('/')
        val normalizedRoot = rootPath.trimEnd('/')
        return normalized.equals(normalizedRoot, ignoreCase = true) ||
            normalized.length <= normalizedRoot.length
    }

    /**
     * Computes the parent folder path, clamping safely to [getRootStoragePath].
     */
    fun getParentPath(currentPath: String): String {
        val rootPath = getRootStoragePath()
        if (isAtRootDirectory(currentPath)) return rootPath
        val parentFile = File(currentPath).parentFile ?: return rootPath
        val parentPath = try {
            parentFile.canonicalPath
        } catch (_: Exception) {
            parentFile.absolutePath
        }
        return if (parentPath.length < rootPath.length) rootPath else parentPath
    }

    /**
     * Formats the header title for the current folder.
     */
    fun getDisplayFolderName(path: String): String {
        if (isAtRootDirectory(path)) return "Internal Storage"
        val file = File(path)
        return file.name.ifBlank { "Internal Storage" }
    }

    /**
     * Formats a concise breadcrumb path for the widget header (e.g. `/storage/emulated/0`).
     */
    fun getTruncatedDisplayPath(path: String): String {
        val rootPath = getRootStoragePath()
        val cleanPath = path.trimEnd('/')
        if (cleanPath == rootPath.trimEnd('/')) {
            return rootPath
        }
        val relative = cleanPath.removePrefix(rootPath.trimEnd('/'))
        val formatted = "/0$relative"
        return if (formatted.length > 36) {
            "…" + formatted.takeLast(33)
        } else {
            formatted
        }
    }

    /**
     * Checks whether [file] is a supported image that should open directly inside the widget's
     * `IMAGE_VIEWER` mode (`.jpg`, `.jpeg`, `.png`, `.webp`, `.bmp`).
     */
    fun isImageFile(file: File): Boolean {
        if (file.isDirectory) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in SUPPORTED_IMAGE_EXTENSIONS
    }

    /**
     * Returns the uppercase file format label (e.g., `"HTML"`, `"TXT"`, `"JAVA"`, `"MD"`).
     * If the file has no extension or the extension exceeds 5 characters, returns `"FILE"`.
     */
    fun getFileFormatBadge(file: File): String {
        if (file.isDirectory) return "DIR"
        val ext = file.extension.trim().uppercase(Locale.ROOT)
        return if (ext.isNotEmpty() && ext.length <= 5) {
            ext
        } else {
            "FILE"
        }
    }

    /**
     * Checks whether [file] is a Markdown file (`.md`, `.markdown`, etc.) that should be
     * rendered with full Markdown formatting instead of plain text.
     */
    fun isMarkdownFile(file: File): Boolean {
        if (file.isDirectory) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in MARKDOWN_EXTENSIONS
    }

    /**
     * Determines whether [file] is a valid text file (such as `.txt`, `.md`, `.java`, `.c`, `.kt`,
     * `.py`, `.json`, `.xml`, `.log`, or extensionless text files) by checking its extension and
     * inspecting the leading bytes of the file to reject binary content.
     */
    fun isValidTextFile(file: File): Boolean {
        if (!file.exists() || file.isDirectory || !file.canRead()) return false
        if (isImageFile(file)) return false

        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext in KNOWN_BINARY_EXTENSIONS) return false

        val lowerName = file.name.lowercase(Locale.ROOT)
        val hasKnownTextExtension = ext in KNOWN_TEXT_EXTENSIONS ||
            lowerName in KNOWN_EXTENSIONLESS_TEXT_NAMES ||
            lowerName.startsWith(".env") ||
            lowerName.startsWith(".git")

        // If the file is larger than 1 MB and has a known text extension, treat it as a valid text
        // file so the 1 MB limit screen + file picker option is shown without reading the whole file.
        if (hasKnownTextExtension && file.length() == 0L) return true
        if (hasKnownTextExtension && file.length() > MAX_TEXT_PREVIEW_BYTES) return true

        return inspectLeadingBytesForText(file, strictAsciiRatio = !hasKnownTextExtension)
    }

    /**
     * Reads up to the first 4096 bytes of [file] to verify it contains valid text (UTF-8 / UTF-16 / ASCII)
     * rather than raw binary bytes.
     */
    private fun inspectLeadingBytesForText(file: File, strictAsciiRatio: Boolean): Boolean {
        return try {
            val length = file.length()
            if (length == 0L) return true
            val sampleSize = length.coerceAtMost(4096L).toInt()
            val buffer = ByteArray(sampleSize)
            val bytesRead = file.inputStream().use { it.read(buffer, 0, sampleSize) }
            if (bytesRead <= 0) return true

            // Check UTF-8 BOM (EF BB BF) or UTF-16 BOM (FF FE / FE FF)
            if (bytesRead >= 3 &&
                (buffer[0].toInt() and 0xFF) == 0xEF &&
                (buffer[1].toInt() and 0xFF) == 0xBB &&
                (buffer[2].toInt() and 0xFF) == 0xBF
            ) {
                return true
            }
            if (bytesRead >= 2) {
                val b0 = buffer[0].toInt() and 0xFF
                val b1 = buffer[1].toInt() and 0xFF
                if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                    return true
                }
            }

            var suspiciousControlBytes = 0
            for (i in 0 until bytesRead) {
                val b = buffer[i].toInt() and 0xFF
                // Null byte in a non-UTF16 file is a strong indicator of binary data
                if (b == 0x00) return false
                // Control characters other than TAB (0x09), LF (0x0A), VT (0x0B), FF (0x0C), CR (0x0D), ESC (0x1B)
                if (b < 0x09 || (b in 0x0E..0x1A) || (b in 0x1C..0x1F)) {
                    suspiciousControlBytes++
                }
            }

            val maxAllowedRatio = if (strictAsciiRatio) 0.02 else 0.05
            (suspiciousControlBytes.toDouble() / bytesRead.toDouble()) <= maxAllowedRatio
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Loads a text or `.md` file from [filePath] for display inside the widget or in-app preview.
     * Enforces the `1 MB` ([MAX_TEXT_PREVIEW_BYTES]) limit; files above 1 MB return
     * [TextFileLoadResult.TooLargeForPreview] so the UI can offer the system file/app picker.
     */
    fun loadTextFileForPreview(filePath: String?): TextFileLoadResult {
        if (filePath.isNullOrBlank()) {
            return TextFileLoadResult.Unreadable(
                fileName = "File",
                reason = "No file path specified."
            )
        }
        val file = File(filePath)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return TextFileLoadResult.Unreadable(
                fileName = file.name.ifBlank { "File" },
                reason = "File does not exist or cannot be read."
            )
        }

        val sizeBytes = file.length()
        if (sizeBytes > MAX_TEXT_PREVIEW_BYTES) {
            return TextFileLoadResult.TooLargeForPreview(
                fileName = file.name,
                fullPath = file.absolutePath,
                fileSizeBytes = sizeBytes,
                formattedSize = formatFileSize(sizeBytes)
            )
        }

        return try {
            val rawText = file.readText(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')

            val isMd = isMarkdownFile(file)
            val mdBlocks = if (isMd) {
                MarkdownHelper.parseMarkdownBlocks(rawText).take(MAX_GLANCE_MARKDOWN_BLOCKS)
            } else {
                emptyList()
            }

            // Group plain text lines into clean scrollable chunks for Glance LazyColumn
            val allLines = if (rawText.isEmpty()) listOf("(Empty file)") else rawText.lines()
            val chunks = allLines
                .chunked(10)
                .take(MAX_GLANCE_TEXT_CHUNKS)
                .map { group -> group.joinToString("\n") }

            TextFileLoadResult.Success(
                fileName = file.name,
                fullPath = file.absolutePath,
                isMarkdown = isMd,
                rawContent = if (rawText.isEmpty()) "(Empty file)" else rawText,
                linesOrChunks = chunks,
                markdownBlocks = mdBlocks
            )
        } catch (e: OutOfMemoryError) {
            TextFileLoadResult.TooLargeForPreview(
                fileName = file.name,
                fullPath = file.absolutePath,
                fileSizeBytes = sizeBytes,
                formattedSize = formatFileSize(sizeBytes)
            )
        } catch (e: Exception) {
            TextFileLoadResult.Unreadable(
                fileName = file.name,
                reason = e.localizedMessage ?: "Unable to read text content."
            )
        }
    }

    /**
     * Lists and sorts the contents of [targetPath]:
     * - Folders first (alphabetically, case-insensitive)
     * - Files second (alphabetically, case-insensitive)
     */
    fun listDirectoryContents(
        context: Context,
        targetPath: String?,
        showHidden: Boolean = false
    ): DirectoryLoadResult {
        if (!hasStoragePermission(context)) {
            return DirectoryLoadResult.PermissionRequired
        }

        ensureSampleFilesExist(context)

        val resolvedPath = normalizePath(targetPath)
        val directory = File(resolvedPath)

        if (!directory.exists()) {
            return DirectoryLoadResult.Unreadable(
                attemptedPath = resolvedPath,
                reason = "Directory does not exist."
            )
        }

        if (!directory.isDirectory || !directory.canRead()) {
            return DirectoryLoadResult.Unreadable(
                attemptedPath = resolvedPath,
                reason = "Directory cannot be read."
            )
        }

        val rawChildren: Array<File> = try {
            directory.listFiles() ?: emptyArray()
        } catch (e: SecurityException) {
            return DirectoryLoadResult.Unreadable(
                attemptedPath = resolvedPath,
                reason = e.localizedMessage ?: "Access restricted."
            )
        }

        val visibleFiles = rawChildren.filter { showHidden || !it.name.startsWith(".") }
        val displayFolderName = getDisplayFolderName(resolvedPath)

        if (visibleFiles.isEmpty()) {
            return DirectoryLoadResult.Empty(
                currentPath = resolvedPath,
                displayFolderName = displayFolderName
            )
        }

        val sortedFiles = visibleFiles.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )

        val totalFolders = sortedFiles.count { it.isDirectory }
        val totalFiles = sortedFiles.size - totalFolders
        val isTruncated = sortedFiles.size > MAX_WIDGET_ITEMS

        val dateFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        val mappedItems = sortedFiles
            .take(MAX_WIDGET_ITEMS)
            .map { file ->
                val isDir = file.isDirectory
                val isImg = isImageFile(file)
                val isMd = isMarkdownFile(file)
                val isTxt = !isDir && !isImg && (isMd || isValidTextFile(file))
                val childCount = if (isDir) {
                    try {
                        file.list()?.count { showHidden || !it.startsWith(".") } ?: 0
                    } catch (_: Exception) {
                        0
                    }
                } else {
                    0
                }
                val sizeBytes = if (isDir) 0L else file.length()
                val modified = file.lastModified()
                val category = resolveFileCategory(file)
                val datePart = if (modified > 0L) dateFormatter.format(Date(modified)) else ""

                val subtitle = if (isDir) {
                    val countLabel = if (childCount == 1) "1 item" else "$childCount items"
                    if (datePart.isNotEmpty()) "$countLabel • $datePart" else countLabel
                } else {
                    val sizeLabel = formatFileSize(sizeBytes)
                    if (datePart.isNotEmpty()) "$sizeLabel • $datePart" else sizeLabel
                }

                FileBrowserItem(
                    name = file.name,
                    absolutePath = file.absolutePath,
                    isDirectory = isDir,
                    isImage = isImg,
                    isText = isTxt,
                    isMarkdown = isMd,
                    sizeBytes = sizeBytes,
                    lastModified = modified,
                    childCount = childCount,
                    category = category,
                    formattedSubtitle = subtitle
                )
            }

        return DirectoryLoadResult.Success(
            currentPath = resolvedPath,
            displayFolderName = displayFolderName,
            items = mappedItems,
            totalFoldersCount = totalFolders,
            totalFilesCount = totalFiles,
            isTruncatedForBinderLimit = isTruncated
        )
    }

    /**
     * Reads an image directly from its [filePath] on device storage and downsamples it safely
     * (max dimension [maxDimension] = `1080px` using `BitmapFactory.Options.inSampleSize` and EXIF
     * orientation handling) so it never causes `TransactionTooLargeException` in RemoteViews.
     */
    fun loadDownsampledBitmap(
        filePath: String?,
        maxDimension: Int = MAX_WIDGET_BITMAP_DIMENSION
    ): Bitmap? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.exists() || !file.isFile || file.length() == 0L) return null

        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(
                rawWidth = boundsOptions.outWidth,
                rawHeight = boundsOptions.outHeight,
                reqWidth = maxDimension,
                reqHeight = maxDimension
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            val rotation = readExifRotation(file.absolutePath)
            val rotated = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
            } else {
                sampled
            }
            if (rotated !== sampled) {
                sampled.recycle()
            }

            val currentMax = max(rotated.width, rotated.height)
            if (currentMax <= maxDimension) {
                return rotated
            }

            val scaleFactor = maxDimension.toFloat() / currentMax.toFloat()
            val targetW = (rotated.width * scaleFactor).roundToInt().coerceAtLeast(1)
            val targetH = (rotated.height * scaleFactor).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
            if (scaled !== rotated) {
                rotated.recycle()
            }
            scaled
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads a downsampled bitmap from [filePath] and renders it onto a viewport canvas matching
     * [containerAspect] at magnification `baseFitScale * zoomFactor` (`1.00f`, `1.20f`, `1.40f`, `1.60f` —
     * 3 steps of +20% each).
     *
     * Because the output bitmap matches the exact aspect ratio of the widget's image container:
     * - Resizing the widget horizontally or vertically dynamically scales the image to utilize all
     *   available vertical or horizontal space.
     * - Every zoom step strictly magnifies both width and height by +20% from the center, expanding
     *   into any free horizontal OR vertical space without ever shrinking the image or adding top/bottom
     *   empty bars.
     */
    fun loadZoomedBitmap(
        filePath: String?,
        zoomFactor: Float = 1.0f,
        containerAspect: Float = 1.0f,
        maxDimension: Int = MAX_WIDGET_BITMAP_DIMENSION
    ): Bitmap? {
        val baseBitmap = loadDownsampledBitmap(filePath, maxDimension) ?: return null

        return try {
            val srcW = baseBitmap.width
            val srcH = baseBitmap.height
            if (srcW <= 0 || srcH <= 0) return baseBitmap

            val targetAspect = containerAspect.coerceIn(0.25f, 4.0f)
            val canvasMax = 840
            val (outW, outH) = if (targetAspect >= 1.0f) {
                canvasMax to (canvasMax / targetAspect).roundToInt().coerceAtLeast(1)
            } else {
                (canvasMax * targetAspect).roundToInt().coerceAtLeast(1) to canvasMax
            }

            val viewportBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(viewportBitmap)
            canvas.drawColor(Color.WHITE)

            // Base scale that fits the entire image inside the current widget viewport (outW x outH)
            val baseFitScale = minOf(
                outW.toFloat() / srcW.toFloat(),
                outH.toFloat() / srcH.toFloat()
            )
            // Apply the 20%-per-step zoom factor directly on top of baseFitScale
            val effectiveScale = baseFitScale * zoomFactor.coerceAtLeast(1.0f)

            val drawW = srcW * effectiveScale
            val drawH = srcH * effectiveScale
            val left = (outW - drawW) / 2f
            val top = (outH - drawH) / 2f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val destRect = RectF(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(baseBitmap, null, destRect, paint)

            if (viewportBitmap !== baseBitmap) {
                baseBitmap.recycle()
            }
            viewportBitmap
        } catch (_: OutOfMemoryError) {
            baseBitmap
        } catch (_: Exception) {
            baseBitmap
        }
    }

    private fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun readExifRotation(filePath: String): Int {
        return try {
            val exif = ExifInterface(filePath)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Renders a crisp Comic Panel header title into a lightweight monochrome [Bitmap] using
     * the bundled `R.font.comic_neue` font so Glance widget headers display authentic comic lettering.
     */
    fun renderComicHeaderBitmap(
        context: Context,
        title: String,
        textColorHex: Int = Color.BLACK,
        uppercase: Boolean = true
    ): Bitmap {
        val displayTitle = if (uppercase) title.uppercase(Locale.ROOT) else title
        val customTypeface = runCatching {
            ResourcesCompat.getFont(context, R.font.comic_neue)
        }.getOrNull() ?: Typeface.MONOSPACE

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColorHex
            textSize = 15f * density
            typeface = Typeface.create(customTypeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }

        val maxChars = 22
        val clipped = if (displayTitle.length > maxChars) {
            displayTitle.take(maxChars - 1) + "…"
        } else {
            displayTitle
        }

        val measuredWidth = paint.measureText(clipped).toInt().coerceAtLeast(40)
        val fm = paint.fontMetrics
        val measuredHeight = (fm.descent - fm.ascent + 4f * density).toInt().coerceAtLeast(24)

        val bitmap = Bitmap.createBitmap(
            measuredWidth.coerceAtMost(480),
            measuredHeight.coerceAtMost(96),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawText(clipped, 0f, -fm.ascent + 2f * density, paint)
        return bitmap
    }

    fun resolveFileCategory(file: File): FileCategory {
        if (file.isDirectory) return FileCategory.FOLDER
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif" -> FileCategory.IMAGE
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v" -> FileCategory.VIDEO
            "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus" -> FileCategory.AUDIO
            "pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
            "json", "xml", "html", "kt", "java", "py", "js", "ts", "css", "log" -> FileCategory.DOCUMENT
            "zip", "rar", "7z", "tar", "gz", "bz2", "apk", "aab" -> FileCategory.ARCHIVE
            else -> FileCategory.GENERIC
        }
    }

    fun resolveMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext.isNotEmpty()) {
            val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!systemMime.isNullOrBlank()) {
                return systemMime
            }
        }
        return when (ext) {
            "md", "txt", "log", "ini", "cfg", "kt", "java", "py", "sh", "yaml", "yml" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val unit = 1024.0
        val exp = (ln(bytes.toDouble()) / ln(unit)).toInt().coerceAtMost(4)
        val prefix = "KMGT"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / unit.pow(exp.toDouble()), prefix)
    }

    fun launchStoragePermissionSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(context, "Unable to open storage settings.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Opens a non-image file in the user's default external app via [FileProvider] and
     * handles [ActivityNotFoundException] safely.
     */
    fun openFileExternally(context: Context, file: File): Result<String> {
        if (!file.exists() || file.isDirectory) {
            return Result.failure(IllegalArgumentException("File does not exist: ${file.name}"))
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = resolveMimeType(file)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(viewIntent)
                Result.success("Opened ${file.name}")
            } catch (_: ActivityNotFoundException) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(fallbackIntent, "Open ${file.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                Result.success("Opened ${file.name}")
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_no_app_for_file),
                Toast.LENGTH_SHORT
            ).show()
            Result.failure(e)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Cannot open ${file.name}",
                Toast.LENGTH_SHORT
            ).show()
            Result.failure(e)
        }
    }

    /**
     * Explicitly opens the Android system app chooser ("Open with...") for [file] via [FileProvider],
     * used when a text file exceeds the 1 MB widget/preview limit or when the user chooses an external app.
     */
    fun openFileWithPicker(context: Context, file: File): Result<String> {
        if (!file.exists() || file.isDirectory) {
            return Result.failure(IllegalArgumentException("File does not exist: ${file.name}"))
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = if (isValidTextFile(file)) "text/plain" else resolveMimeType(file)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, "Open ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success("Opened picker for ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Cannot open app picker for ${file.name}",
                Toast.LENGTH_SHORT
            ).show()
            Result.failure(e)
        }
    }

    /**
     * Ensures sample files (`.png`, `.txt`, `.md`, and `.java`) exist on device storage
     * so fresh emulators have real folders, Markdown docs, code files, and images ready to test.
     */
    private fun ensureSampleFilesExist(context: Context) {
        if (!hasStoragePermission(context)) return
        runCatching {
            val root = File(getRootStoragePath())
            val sampleImageFile = File(root, "Comic_QR_Pass.png")
            if (!sampleImageFile.exists()) {
                val bmp = createSampleComicQrBitmap()
                FileOutputStream(sampleImageFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
            }

            val picturesSample = File(root, "Pictures/Comic_Panel_Poster.png")
            if (!picturesSample.exists()) {
                picturesSample.parentFile?.mkdirs()
                val bmp = createSampleComicQrBitmap()
                FileOutputStream(picturesSample).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
            }

            val readmeFile = File(root, "Documents/Comic_Widget_Guide.txt")
            if (!readmeFile.exists()) {
                readmeFile.parentFile?.mkdirs()
                readmeFile.writeText(
                    "COMIC FILE WIDGET - QUICK START (.TXT)\n" +
                        "======================================\n\n" +
                        "1. Tap any folder to browse inside it.\n" +
                        "2. Tap any image (.png, .jpg, .webp) to view it inside the widget with 2x zoom in/out.\n" +
                        "3. Tap any text or code file (.txt, .java, .c, .kt, .json, .py) up to 1 MB to scroll through it directly.\n" +
                        "4. Tap any Markdown (.md) file to view it with rich Markdown formatting.\n" +
                        "5. Text files larger than 1 MB offer an 'Open with File Picker' button."
                )
            }

            val markdownSample = File(root, "Documents/Comic_Notes.md")
            if (!markdownSample.exists()) {
                markdownSample.parentFile?.mkdirs()
                markdownSample.writeText(
                    "# Comic Panel Markdown Guide\n\n" +
                        "Welcome to the **Styled Markdown Viewer** inside both the *Home Screen Widget* and *In-App Preview*!\n\n" +
                        "## Supported Formatting\n\n" +
                        "- **Bold ink** and *italic lettering*\n" +
                        "- Inline `monospace code` badges and ~~strikethrough~~\n" +
                        "- Interactive [Documentation Links](https://developer.android.com)\n\n" +
                        "### Task Checklist\n" +
                        "- [x] Render `.md` files with proper markup styling\n" +
                        "- [x] Support `.txt`, `.java`, `.c`, and code files\n" +
                        "- [ ] Enforce 1 MB preview limit with File Picker option\n\n" +
                        "> \"Crisp 2dp black ink borders with authentic comic-book clarity.\"\n\n" +
                        "```kotlin\n" +
                        "fun isTextPreviewAllowed(bytes: Long): Boolean {\n" +
                        "    return bytes <= 1_048_576L // 1 MB limit\n" +
                        "}\n" +
                        "```\n\n" +
                        "---\n\n" +
                        "| File Type | Limit | Viewer Style |\n" +
                        "| --- | --- | --- |\n" +
                        "| `.md` | 1 MB | Styled Markdown |\n" +
                        "| `.txt` / `.java` | 1 MB | Scrollable Monospace |"
                )
            }

            val javaSample = File(root, "Documents/SampleCode.java")
            if (!javaSample.exists()) {
                javaSample.parentFile?.mkdirs()
                javaSample.writeText(
                    "package com.example.demo;\n\n" +
                        "public class SampleCode {\n" +
                        "    private static final long MAX_PREVIEW_BYTES = 1024L * 1024L; // 1 MB\n\n" +
                        "    public static void main(String[] args) {\n" +
                        "        System.out.println(\"Hello from SampleCode.java inside Comic Panel Viewer!\");\n" +
                        "        for (int i = 1; i <= 5; i++) {\n" +
                        "            System.out.println(\"Line #\" + i + \" -> scrollable code preview\");\n" +
                        "        }\n" +
                        "    }\n" +
                        "}\n"
                )
            }
        }
    }

    private fun createSampleComicQrBitmap(): Bitmap {
        val size = 640
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 12f
        }

        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), whitePaint)
        canvas.drawRect(24f, 24f, size - 24f, size - 24f, borderPaint)

        val qrMargin = 120f
        val gridCount = 21
        val cell = (size - qrMargin * 2f) / gridCount

        fun drawFinder(startCol: Int, startRow: Int) {
            for (r in 0..6) {
                for (c in 0..6) {
                    val isEdge = r == 0 || r == 6 || c == 0 || c == 6
                    val isCenter = r in 2..4 && c in 2..4
                    if (isEdge || isCenter) {
                        val left = qrMargin + (startCol + c) * cell
                        val top = qrMargin - 20f + (startRow + r) * cell
                        canvas.drawRect(left, top, left + cell, top + cell, blackPaint)
                    }
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(gridCount - 7, 0)
        drawFinder(0, gridCount - 7)

        for (r in 0 until gridCount) {
            for (c in 0 until gridCount) {
                val inCorner = (r < 8 && c < 8) ||
                    (r < 8 && c >= gridCount - 8) ||
                    (r >= gridCount - 8 && c < 8)
                if (!inCorner && ((r * 19 + c * 31 + r * c) % 3 == 0)) {
                    val left = qrMargin + c * cell
                    val top = qrMargin - 20f + r * cell
                    canvas.drawRect(left, top, left + cell * 0.9f, top + cell * 0.9f, blackPaint)
                }
            }
        }

        val bannerRect = RectF(64f, size - 110f, size - 64f, size - 54f)
        canvas.drawRect(bannerRect, blackPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("COMIC PANEL QR • TAP FOR FULLSCREEN", size / 2f, size - 72f, textPaint)
        return bitmap
    }
}
