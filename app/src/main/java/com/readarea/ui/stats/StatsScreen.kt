package com.readarea.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.components.Art
import com.readarea.ui.components.Illustration
import com.readarea.ui.components.StatTile
import com.readarea.ui.components.formatDuration
import java.time.LocalDate
import java.time.format.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.readarea.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(vm: LibraryViewModel, @Suppress("UNUSED_PARAMETER") actions: AppActions) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.reading_stats)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                if (stats.totalMs == 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        Art(Illustration.CHART, Modifier.size(84.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 4) {
                    val tile = Modifier.weight(1f).widthIn(min = 150.dp)
                    StatTile(stringResource(R.string.today), formatDuration(stats.todayMs), tile)
                    StatTile(stringResource(R.string.current_streak), pluralStringResource(R.plurals.days_short, stats.streak, stats.streak), tile)
                    StatTile(stringResource(R.string.best_streak), pluralStringResource(R.plurals.days_short, stats.bestStreak, stats.bestStreak), tile)
                    StatTile(stringResource(R.string.total_time), formatDuration(stats.totalMs), tile)
                    StatTile(stringResource(R.string.books_finished), "${stats.finished}", tile)
                    StatTile(stringResource(R.string.finished_this_year), "${stats.finishedThisYear}", tile)
                    StatTile(stringResource(R.string.pages_turned), "${stats.totalPages}", tile)
                }
                Text(stringResource(R.string.last_7_days), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                val maxMs = (stats.week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(stats.goalMinutes * 60_000L).coerceAtLeast(1L)
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                        val n = stats.week.size.coerceAtLeast(1)
                        val slot = size.width / n
                        val bw = slot * 0.5f
                        val goalY = size.height * (1f - (stats.goalMinutes * 60_000f / maxMs))
                        drawLine(outline, Offset(0f, goalY), Offset(size.width, goalY), strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                        stats.week.forEachIndexed { i, (_, ms) ->
                            val h = size.height * (ms.toFloat() / maxMs)
                            val x = slot * i + (slot - bw) / 2
                            drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                            if (h > 0) drawRoundRect(primary, Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        stats.week.forEach { (d, ms) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall)
                                Text(if (ms > 0) stringResource(R.string.minutes_short, (ms / 60_000).toInt()) else "–", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.last_20_weeks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp).horizontalScroll(rememberScrollState())) {
                    val weeks = 20
                    val today = LocalDate.now()
                    val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
                    Canvas(Modifier.width((weeks * 18).dp).height(126.dp)) {
                        val cell = size.height / 7f
                        val gap = cell * 0.18f
                        val goal = stats.goalMinutes * 60_000f
                        for (w in 0 until weeks) for (dow in 0 until 7) {
                            val day = start.plusDays((w * 7 + dow).toLong())
                            if (day.isAfter(today)) continue
                            val ms = stats.days[day.toEpochDay()] ?: 0L
                            val t = if (ms <= 0) 0f else (0.25f + 0.75f * (ms / goal).coerceIn(0f, 1f))
                            val color = if (t == 0f) track else primary.copy(alpha = t)
                            drawRoundRect(color, Offset(w * cell + gap / 2, dow * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(cell * 0.2f))
                        }
                    }
                }
                Text(stringResource(R.string.daily_goal), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 20, 30, 45, 60, 90, 120).forEach { m ->
                        FilterChip(selected = stats.goalMinutes == m, onClick = { vm.updateSettings { it.copy(dailyGoalMinutes = m) } }, label = { Text(stringResource(R.string.duration_minutes, m)) })
                    }
                }
                if (stats.topBooks.isNotEmpty()) {
                    Text(stringResource(R.string.most_time_spent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    val top = stats.topBooks.first().ms.coerceAtLeast(1)
                    stats.topBooks.forEach { b ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatDuration(b.ms), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                                Box(Modifier.fillMaxWidth(b.ms.toFloat() / top).height(8.dp).clip(RoundedCornerShape(4.dp)).background(primary))
                            }
                        }
                    }
                }
            }
        }
    }
}
