package com.readarea.ui.notes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.reader.ReaderActivity
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.components.EmptyState
import com.readarea.ui.components.Illustration
import com.readarea.ui.components.percent
import com.readarea.ui.theme.highlightPalette
import androidx.compose.ui.res.stringResource
import com.readarea.R

@Composable
fun NotesScreen(vm: LibraryViewModel, @Suppress("UNUSED_PARAMETER") actions: AppActions) {
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val hl = remember(highlights, q) { highlights.filter { q.isEmpty() || it.highlight.text.lowercase().contains(q) || it.highlight.note?.lowercase()?.contains(q) == true || it.bookTitle.lowercase().contains(q) } }
    val bm = remember(bookmarks, q) { bookmarks.filter { q.isEmpty() || it.bookmark.snippet.lowercase().contains(q) || it.bookTitle.lowercase().contains(q) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_notes)) },
                actions = {
                    if (highlights.isNotEmpty()) IconButton(onClick = {
                        val md = buildString {
                            append("# ").append(resources.getString(R.string.export_notes_heading)).append("\n\n")
                            highlights.groupBy { it.bookTitle }.forEach { (title, list) ->
                                append("## ").append(title).append('\n')
                                list.firstOrNull()?.bookAuthor?.takeIf { it.isNotBlank() }?.let { append("*").append(it).append("*\n") }
                                append('\n')
                                list.sortedBy { it.highlight.progress }.forEach { h ->
                                    append("> ").append(h.highlight.text.replace("\n", "\n> ")).append("\n\n")
                                    h.highlight.note?.let { append(it).append("\n\n") }
                                }
                            }
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.export_notes_heading)).putExtra(Intent.EXTRA_TEXT, md), resources.getString(R.string.export_notes)))
                    }) { Icon(Icons.Rounded.Share, stringResource(R.string.export_all)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_highlights, highlights.size)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_bookmarks, bookmarks.size)) })
            }
            if (highlights.isNotEmpty() || bookmarks.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text(stringResource(R.string.search_notes)) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (tab == 0) {
                    if (hl.isEmpty()) {
                        EmptyState(Illustration.NOTES, stringResource(if (highlights.isEmpty()) R.string.no_highlights else R.string.no_matches_title), stringResource(R.string.no_highlights_body))
                    } else {
                        LazyColumn(Modifier.widthIn(max = 840.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(hl, key = { it.highlight.id }) { item ->
                                val h = item.highlight
                                Row(
                                    Modifier.fillMaxWidth().clickable { context.startActivity(ReaderActivity.intent(context, h.bookId, h.chapter, h.start)) }.padding(horizontal = 20.dp, vertical = 12.dp),
                                ) {
                                    Box(Modifier.width(4.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(highlightPalette.getOrElse(h.color) { highlightPalette[0] }))
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(h.text, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                        h.note?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text("${item.bookTitle} · ${h.chapterTitle}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Column {
                                        IconButton(onClick = {
                                            val quote = "“${h.text}”\n— ${item.bookTitle}${if (item.bookAuthor.isNotBlank()) ", ${item.bookAuthor}" else ""}"
                                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote), resources.getString(R.string.share_quote)))
                                        }) { Icon(Icons.Rounded.Share, stringResource(R.string.share), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        IconButton(onClick = { vm.deleteHighlight(h.id) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (bm.isEmpty()) {
                        EmptyState(Illustration.NOTES, stringResource(if (bookmarks.isEmpty()) R.string.no_bookmarks else R.string.no_matches_title), stringResource(R.string.no_bookmarks_body))
                    } else {
                        LazyColumn(Modifier.widthIn(max = 840.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(bm, key = { it.bookmark.id }) { item ->
                                val b = item.bookmark
                                Row(
                                    Modifier.fillMaxWidth().clickable { context.startActivity(ReaderActivity.intent(context, b.bookId, b.chapter, b.offset)) }.padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(b.snippet, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text("${item.bookTitle} · ${percent(b.progress)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { vm.deleteBookmark(b.id) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
