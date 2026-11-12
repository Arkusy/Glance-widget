package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ComicBlack
import com.example.ui.theme.ComicWhite
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.comicPanelFrame
import java.io.File

/**
 * Minimal, borderless Comic Panel full-screen image viewer launched when the user taps
 * the displayed image inside [UnifiedComicWidget] in `IMAGE_VIEWER` mode.
 *
 * Supports full pinch-to-zoom (`scale` 1x–5x), panning (`Modifier.transformable`),
 * double-tap zoom toggle, and a clean `← Back` action.
 */
class FullscreenImageActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val imagePath = intent?.getStringExtra(EXTRA_IMAGE_PATH)

        setContent {
            MyApplicationTheme {
                FullscreenComicImageViewer(
                    imagePath = imagePath,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
fun FullscreenComicImageViewer(
    imagePath: String?,
    onClose: () -> Unit
) {
    val bitmap = remember(imagePath) {
        FileHelper.loadDownsampledBitmap(imagePath, maxDimension = 2048)
    }
    val fileName = remember(imagePath) {
        imagePath?.let { File(it).name } ?: "IMAGE"
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset = if (newScale > 1f) {
            offset + panChange
        } else {
            Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComicWhite)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .transformable(state = transformableState)
            .testTag("fullscreen_image_container"),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        } else {
            Text(
                text = "IMAGE NOT FOUND",
                style = MaterialTheme.typography.titleLarge,
                color = ComicBlack
            )
        }

        // Minimal Top Comic Panel Control Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .comicPanelFrame(backgroundColor = ComicWhite, borderColor = ComicBlack)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("fullscreen_back_button")
            ) {
                Text(
                    text = "← BACK",
                    style = MaterialTheme.typography.labelLarge,
                    color = ComicBlack
                )
            }

            if (scale > 1.05f) {
                Box(
                    modifier = Modifier
                        .comicPanelFrame(backgroundColor = ComicBlack, borderColor = ComicBlack)
                        .clickable {
                            scale = 1f
                            offset = Offset.Zero
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("fullscreen_reset_zoom_button")
                ) {
                    Text(
                        text = "RESET ${(scale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
