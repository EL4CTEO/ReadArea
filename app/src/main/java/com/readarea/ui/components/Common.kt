package com.readarea.ui.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookEntity
import com.readarea.data.db.BookStatus
import androidx.compose.ui.res.stringResource
import com.readarea.R

@Composable
fun formatDuration(ms: Long): String = durationText(LocalContext.current, ms)

fun durationText(context: Context, ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> if (ms > 0) context.getString(R.string.duration_under_minute) else context.getString(R.string.duration_minutes, 0)
        min < 60 -> context.getString(R.string.duration_minutes, min.toInt())
        else -> context.getString(R.string.duration_hours_minutes, (min / 60).toInt(), (min % 60).toInt())
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024f / 1024f)
}

@Composable
fun timeLeft(book: BookEntity): String? {
    if (book.progress < 0.03f || book.readingMs < 120_000 || book.progress >= 0.999f) return null
    val left = (book.readingMs / book.progress * (1 - book.progress)).toLong()
    return stringResource(R.string.time_left, formatDuration(left))
}

@Composable
fun percent(value: Float): String = stringResource(R.string.percent, (value * 100).toInt().coerceIn(0, 100))

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.height(height).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

@Composable
fun BookGridItem(book: BookEntity, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(6.dp),
    ) {
        Box {
            BookCover(book, Modifier.fillMaxWidth())
            if (book.favorite) Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFE5484D), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
            if (book.status == BookStatus.FINISHED) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp).clip(CircleShape).background(Color(0xFF3E8E5A)))
            if (selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
        }
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (book.progress > 0f && book.status != BookStatus.FINISHED) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressLine(book.progress, Modifier.weight(1f), 3.dp)
                Spacer(Modifier.width(6.dp))
                Text(percent(book.progress), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BookListItem(book: BookEntity, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, showBadge: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.width(52.dp), corner = 6.dp, elevation = 2.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBadge) FormatBadge(BookFormat.byName(book.format))
                if (book.favorite) Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFE5484D), modifier = Modifier.padding(start = 6.dp).size(14.dp))
                Spacer(Modifier.width(8.dp))
                when {
                    book.status == BookStatus.FINISHED -> Text(stringResource(R.string.status_finished), style = MaterialTheme.typography.labelSmall, color = Color(0xFF3E8E5A))
                    book.progress > 0f -> {
                        ProgressLine(book.progress, Modifier.widthIn(max = 120.dp).weight(1f, fill = false).width(120.dp), 3.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(percent(book.progress), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    book.status == BookStatus.WANT -> Text(stringResource(R.string.status_want), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    else -> Text(stringResource(R.string.status_new), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun FormatBadge(format: BookFormat) {
    Text(
        format.label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onAction).padding(8.dp),
            )
        }
    }
}

enum class Illustration { BOOKS, NOTES, SHELF, CHART, SEARCH }

@Composable
fun EmptyState(illustration: Illustration, title: String, body: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(modifier.padding(32.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Art(illustration, Modifier.size(150.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
fun Art(kind: Illustration, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val container = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        drawCircle(container.copy(alpha = 0.55f), radius = size.minDimension * 0.48f)
        when (kind) {
            Illustration.BOOKS -> drawBooks(primary, tertiary, surface, ink)
            Illustration.NOTES -> drawNotes(primary, surface, ink)
            Illustration.SHELF -> drawShelf(primary, tertiary, ink)
            Illustration.CHART -> drawChart(primary, tertiary, ink)
            Illustration.SEARCH -> drawSearch(primary, ink)
        }
    }
}

private fun DrawScope.drawBooks(a: Color, b: Color, page: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val cr = CornerRadius(w * 0.02f)
    drawRoundRect(a, Offset(w * 0.22f, h * 0.60f), Size(w * 0.56f, h * 0.1f), cr)
    drawRoundRect(b, Offset(w * 0.27f, h * 0.50f), Size(w * 0.5f, h * 0.1f), cr)
    drawRoundRect(a.copy(alpha = 0.7f), Offset(w * 0.24f, h * 0.40f), Size(w * 0.52f, h * 0.1f), cr)
    val left = Path().apply {
        moveTo(w * 0.5f, h * 0.36f)
        cubicTo(w * 0.42f, h * 0.30f, w * 0.32f, h * 0.29f, w * 0.24f, h * 0.31f)
        lineTo(w * 0.24f, h * 0.14f)
        cubicTo(w * 0.32f, h * 0.12f, w * 0.42f, h * 0.13f, w * 0.5f, h * 0.19f)
        close()
    }
    val right = Path().apply {
        moveTo(w * 0.5f, h * 0.36f)
        cubicTo(w * 0.58f, h * 0.30f, w * 0.68f, h * 0.29f, w * 0.76f, h * 0.31f)
        lineTo(w * 0.76f, h * 0.14f)
        cubicTo(w * 0.68f, h * 0.12f, w * 0.58f, h * 0.13f, w * 0.5f, h * 0.19f)
        close()
    }
    drawPath(left, page)
    drawPath(right, page)
    drawPath(left, ink.copy(alpha = 0.4f), style = Stroke(w * 0.008f))
    drawPath(right, ink.copy(alpha = 0.4f), style = Stroke(w * 0.008f))
    for (i in 0 until 3) {
        drawLine(ink.copy(alpha = 0.35f), Offset(w * 0.29f, h * (0.19f + i * 0.035f)), Offset(w * 0.45f, h * (0.21f + i * 0.035f)), strokeWidth = w * 0.01f)
        drawLine(ink.copy(alpha = 0.35f), Offset(w * 0.55f, h * (0.21f + i * 0.035f)), Offset(w * 0.71f, h * (0.19f + i * 0.035f)), strokeWidth = w * 0.01f)
    }
}

private fun DrawScope.drawNotes(a: Color, page: Color, ink: Color) {
    val w = size.width
    val h = size.height
    rotate(-8f) {
        drawRoundRect(page, Offset(w * 0.26f, h * 0.2f), Size(w * 0.48f, h * 0.6f), CornerRadius(w * 0.04f))
    }
    drawRoundRect(page, Offset(w * 0.3f, h * 0.22f), Size(w * 0.44f, h * 0.56f), CornerRadius(w * 0.04f))
    drawRoundRect(a.copy(alpha = 0.35f), Offset(w * 0.36f, h * 0.34f), Size(w * 0.3f, h * 0.05f), CornerRadius(w * 0.01f))
    for (i in 0 until 4) drawLine(ink.copy(alpha = 0.3f), Offset(w * 0.37f, h * (0.46f + i * 0.07f)), Offset(w * (if (i == 3) 0.52f else 0.66f), h * (0.46f + i * 0.07f)), strokeWidth = w * 0.012f)
    val ribbon = Path().apply {
        moveTo(w * 0.6f, h * 0.22f)
        lineTo(w * 0.68f, h * 0.22f)
        lineTo(w * 0.68f, h * 0.36f)
        lineTo(w * 0.64f, h * 0.32f)
        lineTo(w * 0.6f, h * 0.36f)
        close()
    }
    drawPath(ribbon, a)
}

private fun DrawScope.drawShelf(a: Color, b: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val colors = listOf(a, b, a.copy(alpha = 0.6f), b.copy(alpha = 0.7f), a.copy(alpha = 0.85f))
    val heights = listOf(0.34f, 0.28f, 0.38f, 0.3f, 0.33f)
    var x = w * 0.22f
    colors.forEachIndexed { i, c ->
        val bw = w * (0.08f + (i % 2) * 0.02f)
        drawRoundRect(c, Offset(x, h * 0.68f - h * heights[i]), Size(bw, h * heights[i]), CornerRadius(w * 0.012f))
        x += bw + w * 0.012f
    }
    drawRoundRect(ink.copy(alpha = 0.5f), Offset(w * 0.16f, h * 0.68f), Size(w * 0.68f, h * 0.03f), CornerRadius(w * 0.01f))
}

private fun DrawScope.drawChart(a: Color, b: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val bars = listOf(0.2f, 0.34f, 0.26f, 0.42f, 0.3f)
    bars.forEachIndexed { i, v ->
        drawRoundRect(if (i == 3) a else b.copy(alpha = 0.6f), Offset(w * (0.24f + i * 0.11f), h * 0.7f - h * v), Size(w * 0.07f, h * v), CornerRadius(w * 0.02f))
    }
    drawLine(ink.copy(alpha = 0.5f), Offset(w * 0.2f, h * 0.71f), Offset(w * 0.8f, h * 0.71f), strokeWidth = w * 0.012f)
}

private fun DrawScope.drawSearch(a: Color, ink: Color) {
    val w = size.width
    val h = size.height
    drawCircle(a, radius = w * 0.16f, center = Offset(w * 0.45f, h * 0.44f), style = Stroke(w * 0.045f))
    drawLine(ink.copy(alpha = 0.7f), Offset(w * 0.56f, h * 0.56f), Offset(w * 0.7f, h * 0.7f), strokeWidth = w * 0.05f)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
