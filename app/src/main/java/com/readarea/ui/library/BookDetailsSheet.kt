package com.readarea.ui.library

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookStatus
import com.readarea.ui.AppActions
import com.readarea.ui.GroupKey
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.components.BookCover
import com.readarea.ui.components.FormatBadge
import com.readarea.ui.components.ProgressLine
import com.readarea.ui.components.formatDuration
import com.readarea.ui.components.formatSize
import com.readarea.ui.components.timeLeft
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailsSheet(vm: LibraryViewModel, actions: AppActions, id: Long, onDismiss: () -> Unit) {
    val book by remember(id) { vm.book(id) }.collectAsStateWithLifecycle(null)
    val shelves by vm.collections.collectAsStateWithLifecycle()
    val inShelves by remember(id) { vm.collectionsFor(id) }.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    var shelfDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, sheetMaxWidth = 720.dp) {
        val b = book ?: return@ModalBottomSheet
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Row {
                BookCover(b, Modifier.width(120.dp), elevation = 8.dp)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (b.author.isNotBlank()) {
                        Text(
                            b.author,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp).then(Modifier),
                        )
                    }
                    b.series?.let { Text("$it${b.seriesIndex?.let { i -> " · #${if (i % 1f == 0f) i.toInt() else i}" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormatBadge(BookFormat.byName(b.format))
                        Spacer(Modifier.width(8.dp))
                        Text(formatSize(b.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        (1..5).forEach { i ->
                            IconButton(onClick = { vm.setRating(b.id, if (b.rating == i) 0 else i) }, modifier = Modifier.size(32.dp)) {
                                Icon(if (i <= b.rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Rate $i", tint = if (i <= b.rating) Color(0xFFE0A84C) else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    onDismiss()
                    actions.openBook(b.id)
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (b.progress > 0f) "Continue · ${(b.progress * 100).toInt()}%" else "Start reading")
                }
                IconButton(onClick = { vm.setFavorite(listOf(b.id), !b.favorite) }) {
                    Icon(if (b.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (b.favorite) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val uri = b.uri.toUri()
                    val shareUri = if (uri.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(uri.path!!)) else uri
                    val intent = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(shareUri) ?: "application/octet-stream").putExtra(Intent.EXTRA_STREAM, shareUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, "Share book")) }
                }) { Icon(Icons.Rounded.Share, "Share file") }
            }
            if (b.progress > 0f) {
                Spacer(Modifier.height(12.dp))
                ProgressLine(b.progress, Modifier.fillMaxWidth(), 6.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        "${(b.progress * 100).toInt()}% read",
                        if (b.readingMs > 0) "${formatDuration(b.readingMs)} spent" else null,
                        timeLeft(b),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val opts = listOf(BookStatus.WANT to "Want to read", BookStatus.READING to "Reading", BookStatus.FINISHED to "Finished")
                opts.forEachIndexed { i, (st, label) ->
                    SegmentedButton(selected = b.status == st, onClick = { vm.setStatus(listOf(b.id), if (b.status == st) BookStatus.NEW else st) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size), label = { Text(label, maxLines = 1) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Shelves", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shelves.filter { it.collection.id in inShelves }.forEach { c ->
                    InputChip(selected = true, onClick = { vm.removeFromCollection(c.collection.id, listOf(b.id)) }, label = { Text(c.collection.name) }, trailingIcon = { Text("×") })
                }
                FilterChip(selected = false, onClick = { shelfDialog = true }, label = { Text("Add") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
            }
            b.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(16.dp))
                Text("About", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Serif)
            }
            Spacer(Modifier.height(16.dp))
            Text("Details", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
            InfoRow("File", b.fileName)
            b.publisher?.takeIf { it.isNotBlank() }?.let { InfoRow("Publisher", it) }
            b.language?.takeIf { it.isNotBlank() }?.let { InfoRow("Language", it) }
            if (b.pageCount > 0) InfoRow("Pages", "${b.pageCount}")
            InfoRow("Added", df.format(Date(b.addedAt)))
            if (b.lastOpenedAt > 0) InfoRow("Last read", df.format(Date(b.lastOpenedAt)))
            if (b.finishedAt > 0) InfoRow("Finished", df.format(Date(b.finishedAt)))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.author.isNotBlank()) OutlinedButton(onClick = { onDismiss(); actions.navigate(GroupKey("author", b.author)) }) { Text("More by author") }
                if (b.progress > 0f) TextButton(onClick = { vm.resetProgress(listOf(b.id)) }) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
                TextButton(onClick = { removeDialog = true }) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (shelfDialog) AddToShelfDialog(vm, listOf(id)) { shelfDialog = false }
    if (removeDialog) {
        RemoveDialog(1, onDismiss = { removeDialog = false }) { del ->
            vm.remove(listOf(id), del)
            removeDialog = false
            onDismiss()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
