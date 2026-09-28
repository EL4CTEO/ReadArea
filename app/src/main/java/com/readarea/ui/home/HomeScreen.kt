package com.readarea.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.data.db.BookEntity
import com.readarea.data.db.BookStatus
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryKey
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.SettingsKey
import com.readarea.ui.StatsKey
import com.readarea.ui.StatsUi
import com.readarea.ui.components.BookCover
import com.readarea.ui.components.EmptyState
import com.readarea.ui.components.Illustration
import com.readarea.ui.components.ProgressLine
import com.readarea.ui.components.SectionHeader
import com.readarea.ui.components.formatDuration
import com.readarea.ui.components.percent
import com.readarea.ui.components.timeLeft
import java.time.LocalTime
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.readarea.R

@Composable
fun HomeScreen(vm: LibraryViewModel, actions: AppActions) {
    val books by vm.allBooks.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val list = books.orEmpty()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(greeting()), style = MaterialTheme.typography.headlineSmall)
                        if (stats.streak > 1) Text(pluralStringResource(R.plurals.reading_streak, stats.streak, stats.streak), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = { IconButton(onClick = { actions.navigate(SettingsKey) }) { Icon(Icons.Rounded.Settings, stringResource(R.string.nav_settings)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        if (books != null && list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Illustration.BOOKS, stringResource(if (scan.running) R.string.scanning else R.string.welcome_title), stringResource(R.string.welcome_body)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp))
                        Button(onClick = actions.findBooks, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.TravelExplore, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.find_books))
                        }
                        FilledTonalButton(onClick = actions.addFolder, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.CreateNewFolder, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_folder))
                        }
                        TextButton(onClick = actions.importFiles, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.NoteAdd, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.open_files))
                        }
                    }
                }
            }
            return@Scaffold
        }
        val current = list.filter { it.lastOpenedAt > 0 && it.status != BookStatus.FINISHED }.maxByOrNull { it.lastOpenedAt }
        val reading = list.filter { it.status == BookStatus.READING && it.id != current?.id }.sortedByDescending { it.lastOpenedAt }
        val recent = list.sortedByDescending { it.addedAt }.take(20)
        val want = list.filter { it.status == BookStatus.WANT }
        val finished = list.filter { it.status == BookStatus.FINISHED }.sortedByDescending { it.finishedAt }
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp + padding.calculateBottomPadding())) {
            // Keyed, so a row keeps its own scroll position when another appears above it: the scan bar comes and goes,
            // and "Also reading" shows up once a second book is started.
            if (scan.running) item(key = "scan") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) }
            item(key = "top") {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (maxWidth >= 640.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.weight(1.6f)) { Hero(current, actions) }
                            Box(Modifier.weight(1f)) { GoalCard(stats) { actions.navigate(StatsKey) } }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Hero(current, actions)
                            GoalCard(stats) { actions.navigate(StatsKey) }
                        }
                    }
                }
            }
            if (reading.isNotEmpty()) {
                item(key = "reading-title") { SectionHeader(stringResource(R.string.home_also_reading)) }
                item(key = "reading") { BookRow(reading, actions) }
            }
            item(key = "recent-title") { SectionHeader(stringResource(R.string.home_recently_added), stringResource(R.string.see_all)) { actions.navigate(LibraryKey) } }
            item(key = "recent") { BookRow(recent, actions) }
            if (want.isNotEmpty()) {
                item(key = "want-title") { SectionHeader(stringResource(R.string.status_want)) }
                item(key = "want") { BookRow(want, actions) }
            }
            if (finished.isNotEmpty()) {
                item(key = "finished-title") { SectionHeader(stringResource(R.string.status_finished)) }
                item(key = "finished") { BookRow(finished.take(20), actions) }
            }
        }
    }
}

private fun greeting(): Int {
    val h = LocalTime.now().hour
    return when (h) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        in 18..22 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }
}

@Composable
private fun Hero(book: BookEntity?, actions: AppActions) {
    Card(
        onClick = { book?.let { actions.openBook(it.id) } },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (book == null) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.hero_empty_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.hero_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { actions.navigate(LibraryKey) }) { Text(stringResource(R.string.browse_library)) }
            }
            return@Card
        }
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(104.dp).clickable { actions.showDetails(book.id) }, elevation = 10.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.continue_reading), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                ProgressLine(book.progress, Modifier.fillMaxWidth(), 6.dp)
                Spacer(Modifier.height(6.dp))
                Text(listOfNotNull(percent(book.progress), timeLeft(book)).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { actions.openBook(book.id) }) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.read))
                }
            }
        }
    }
}

@Composable
private fun GoalCard(stats: StatsUi, onClick: () -> Unit) {
    val goalMs = stats.goalMinutes * 60_000L
    val p = if (goalMs > 0) (stats.todayMs.toFloat() / goalMs).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Card(onClick = onClick, shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(84.dp)) {
                    val sw = 10.dp.toPx()
                    val inset = sw / 2
                    drawArc(track, -90f, 360f, false, topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(primary, -90f, 360f * p, false, topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text(percent(p), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(stringResource(R.string.today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.goal_progress, formatDuration(stats.todayMs), stats.goalMinutes), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (stats.streak > 0) androidx.compose.ui.graphics.Color(0xFFE8833A) else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (stats.streak > 0) pluralStringResource(R.plurals.day_streak, stats.streak, stats.streak) else stringResource(R.string.start_streak), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BookRow(books: List<BookEntity>, actions: AppActions) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(books, key = { it.id }) { b ->
            Column(Modifier.width(112.dp).combinedClickable(onClick = { actions.openBook(b.id) }, onLongClick = { actions.showDetails(b.id) })) {
                BookCover(b, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(b.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (b.progress > 0f && b.status != BookStatus.FINISHED) {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(b.progress, Modifier.fillMaxWidth(), 3.dp)
                }
            }
        }
    }
}
