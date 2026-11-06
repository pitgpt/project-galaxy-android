package com.pitgpt.galaxy.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AvatarVisualSpec(
    val title: String,
    val description: String,
    val symbol: String,
    val colors: List<Color>,
    val foreground: Color
)

internal fun avatarVisualSpec(id: String?): AvatarVisualSpec = when (id) {
    "avatar.cyber" -> AvatarVisualSpec(
        title = "Кибер",
        description = "Холодный неоновый техно-образ",
        symbol = "⌁",
        colors = listOf(Color(0xFF45D6E8), Color(0xFF3453A5), Color(0xFF171D3B)),
        foreground = Color(0xFFE4FFFF)
    )
    "avatar.minimal" -> AvatarVisualSpec(
        title = "Минимал",
        description = "Чистые формы и мягкое свечение",
        symbol = "◉",
        colors = listOf(Color(0xFFE7EAF4), Color(0xFF8795B8), Color(0xFF3A4566)),
        foreground = Color(0xFF202A45)
    )
    else -> AvatarVisualSpec(
        title = "Космос",
        description = "Звёздное сияние и глубокий синий",
        symbol = "✦",
        colors = listOf(Color(0xFFE2F4FF), Color(0xFF7589D8), Color(0xFF28366D)),
        foreground = Color(0xFF172448)
    )
}

@Composable
internal fun AvatarPortrait(
    avatarId: String?,
    modifier: Modifier = Modifier,
    imageUri: String? = null
) {
    val visual = avatarVisualSpec(avatarId)
    val context = LocalContext.current
    val imageBitmap = produceState<Bitmap?>(initialValue = null, imageUri) {
        value = if (imageUri.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
                context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(input, null, options)
                }
            }.getOrNull()
        }
    }.value
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.radialGradient(visual.colors))
            .border(1.dp, Color(0x99D8EEFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap.asImageBitmap(),
                contentDescription = "Аватар персонажа",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize(0.76f).border(1.dp, visual.foreground.copy(alpha = 0.38f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    visual.symbol,
                    color = visual.foreground,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }
    }
}
