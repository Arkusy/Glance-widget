package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ComicPanelColorScheme = lightColorScheme(
    primary = ComicBlack,
    onPrimary = ComicWhite,
    primaryContainer = ComicPanelGray,
    onPrimaryContainer = ComicBlack,
    secondary = ComicBlack,
    onSecondary = ComicWhite,
    secondaryContainer = ComicPanelGray,
    onSecondaryContainer = ComicBlack,
    background = ComicWhite,
    onBackground = ComicBlack,
    surface = ComicWhite,
    onSurface = ComicBlack,
    surfaceVariant = ComicPanelGray,
    onSurfaceVariant = ComicMutedInk,
    outline = ComicBlack,
    error = ComicBlack,
    onError = ComicWhite
)

private val ComicPanelShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp)
)

/**
 * Applies the signature Comic Panel visual framing:
 * Pure crisp white background (`#FFFFFF`) with a uniform sharp `2.dp` black ink border (`#000000`).
 */
fun Modifier.comicPanelFrame(
    backgroundColor: Color = ComicWhite,
    borderColor: Color = ComicBlack,
    borderWidth: Dp = 2.dp,
    cornerRadius: Dp = 2.dp
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(backgroundColor, shape)
        .border(borderWidth, borderColor, shape)
}

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ComicPanelColorScheme,
        typography = Typography,
        shapes = ComicPanelShapes,
        content = content
    )
}
