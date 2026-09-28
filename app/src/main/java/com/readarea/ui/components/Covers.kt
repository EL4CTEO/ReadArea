package com.readarea.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import com.readarea.R

object CoverCache {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(48 shl 20)) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(path: String): ImageBitmap? = cache.get(path)

    fun load(path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val f = File(path)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 300) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val img = bmp.asImageBitmap()
        cache.put(path, img)
        return img
    }
}

/**
 * The cover image at [path]: null while it loads, and when there is no cover. The state is keyed on [path], so a
 * card that stays on screen while its book changes (the home screen's "Continue reading") drops the old cover in the
 * same pass instead of keeping it: `produceState` would restart its loader but keep showing the previous value.
 */
@Composable
private fun rememberCover(path: String?): ImageBitmap? {
    var image by remember(path) { mutableStateOf(path?.let { CoverCache.get(it) }) }
    LaunchedEffect(path) {
        if (path != null && image == null) image = withContext(Dispatchers.IO) { CoverCache.load(path) }
    }
    return image
}

@Composable
fun BookCover(book: BookEntity, modifier: Modifier = Modifier, corner: Dp = 8.dp, elevation: Dp = 4.dp) {
    val image = rememberCover(book.coverPath)
    val shape = RoundedCornerShape(topStart = corner / 2, bottomStart = corner / 2, topEnd = corner, bottomEnd = corner)
    Box(modifier.aspectRatio(0.68f).shadow(elevation, shape).clip(shape)) {
        if (image != null) {
            Image(image, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(book.title, book.author, BookFormat.byName(book.format), Modifier.fillMaxSize())
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.White.copy(alpha = 0.10f), Color.Transparent), endX = size.width * 0.09f))
            drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(size.width * 0.035f, 0f), size = Size(1.2f, size.height))
        }
    }
}

private data class CoverPalette(val top: Color, val bottom: Color, val ink: Color, val accent: Color)

private val palettes = listOf(
    CoverPalette(Color(0xFF2E4057), Color(0xFF1B2838), Color(0xFFF2E9DC), Color(0xFFE0A84C)),
    CoverPalette(Color(0xFF9A5B34), Color(0xFF6E3D22), Color(0xFFFBEFDD), Color(0xFFF3C27A)),
    CoverPalette(Color(0xFF4F7A5A), Color(0xFF30503A), Color(0xFFF1F5EA), Color(0xFFD9C27A)),
    CoverPalette(Color(0xFF7D4E7A), Color(0xFF4F2D4D), Color(0xFFF7ECF4), Color(0xFFE8B4C8)),
    CoverPalette(Color(0xFFB0463C), Color(0xFF7A2A24), Color(0xFFFFF1E8), Color(0xFFF2C14E)),
    CoverPalette(Color(0xFF3F6E8C), Color(0xFF244257), Color(0xFFEAF3F8), Color(0xFF9FD3E8)),
    CoverPalette(Color(0xFFE9DFCC), Color(0xFFD7C8AC), Color(0xFF3A2E22), Color(0xFFA0522D)),
    CoverPalette(Color(0xFF1F1F24), Color(0xFF0E0E12), Color(0xFFEDE6DA), Color(0xFFC9A45C)),
    CoverPalette(Color(0xFF5E5CA8), Color(0xFF3A3875), Color(0xFFF0EFFB), Color(0xFFF5B971)),
    CoverPalette(Color(0xFFD9A441), Color(0xFFB07C24), Color(0xFF2B2014), Color(0xFF7A3E1D)),
)

@Composable
fun GeneratedCover(title: String, author: String, format: BookFormat, modifier: Modifier = Modifier) {
    val hash = remember(title, author) { abs((title + "|" + author).hashCode()) }
    val p = palettes[hash % palettes.size]
    val pattern = (hash / palettes.size) % 5
    Box(modifier.background(Brush.verticalGradient(listOf(p.top, p.bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a = p.accent
            when (pattern) {
                0 -> {
                    for (i in 0 until 7) {
                        val y = h * (0.62f + i * 0.055f)
                        val path = Path()
                        path.moveTo(0f, y)
                        var x = 0f
                        while (x <= w) {
                            path.lineTo(x, y + sin((x / w) * 6.28f + i) * h * 0.012f)
                            x += w / 24f
                        }
                        drawPath(path, a.copy(alpha = 0.35f - i * 0.04f), style = Stroke(width = w * 0.012f))
                    }
                }
                1 -> {
                    drawCircle(a.copy(alpha = 0.85f), radius = w * 0.22f, center = Offset(w * 0.72f, h * 0.72f))
                    drawCircle(p.ink.copy(alpha = 0.12f), radius = w * 0.34f, center = Offset(w * 0.72f, h * 0.72f), style = Stroke(w * 0.01f))
                    drawCircle(p.ink.copy(alpha = 0.08f), radius = w * 0.46f, center = Offset(w * 0.72f, h * 0.72f), style = Stroke(w * 0.008f))
                }
                2 -> {
                    val step = w / 9f
                    for (i in -12..12) {
                        drawLine(a.copy(alpha = 0.16f), Offset(i * step, h), Offset(i * step + h * 0.6f, h * 0.4f), strokeWidth = w * 0.02f)
                    }
                }
                3 -> {
                    val step = w / 8f
                    for (r in 0 until 5) for (c in 0 until 8) {
                        drawCircle(a.copy(alpha = 0.18f + ((r + c) % 3) * 0.08f), radius = w * 0.018f, center = Offset(step * c + step / 2, h * 0.64f + r * step * 0.9f))
                    }
                }
                else -> {
                    drawRoundRect(a.copy(alpha = 0.9f), topLeft = Offset(w * 0.1f, h * 0.08f), size = Size(w * 0.8f, h * 0.012f), cornerRadius = CornerRadius(4f))
                    drawRoundRect(a.copy(alpha = 0.9f), topLeft = Offset(w * 0.1f, h * 0.6f), size = Size(w * 0.8f, h * 0.012f), cornerRadius = CornerRadius(4f))
                    drawRect(p.ink.copy(alpha = 0.08f), topLeft = Offset(w * 0.1f, h * 0.64f), size = Size(w * 0.8f, h * 0.26f))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp)) {
            val label = title.ifBlank { stringResource(R.string.untitled) }
            val longest = label.split(' ', '-').maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
            val scale = maxWidth.value / 110f
            val size = (maxWidth.value / (longest * 0.62f)).coerceIn(8f, 15f * scale.coerceIn(0.7f, 1.6f))
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                Text(
                    label,
                    color = p.ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = size.sp,
                    lineHeight = (size * 1.2f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (author.isNotBlank()) {
                    Text(author, color = p.ink.copy(alpha = 0.85f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
                Text(format.label.uppercase(), color = p.ink.copy(alpha = 0.55f), fontSize = 8.sp, letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
