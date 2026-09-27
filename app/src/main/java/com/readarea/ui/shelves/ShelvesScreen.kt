package com.readarea.ui.shelves

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookEntity
import com.readarea.data.db.CollectionEntity
import com.readarea.ui.AppActions
import com.readarea.ui.GroupKey
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.ShelfKey
import com.readarea.ui.components.BookCover
import com.readarea.ui.components.BookListItem
import com.readarea.ui.components.EmptyState
import com.readarea.ui.components.Illustration
import com.readarea.ui.components.SectionHeader
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.readarea.R

@Composable
fun ShelvesScreen(vm: LibraryViewModel, actions: AppActions) {
    val shelves by vm.collections.collectAsStateWithLifecycle()
    val books by vm.allBooks.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    val all = books.orEmpty()
    val authors = remember(all) { all.filter { it.author.isNotBlank() }.groupBy { it.author }.toList().sortedByDescending { it.second.size } }
    val series = remember(all) { all.mapNotNull { b -> b.series?.let { it to b } }.groupBy({ it.first }, { it.second }).toList().sortedBy { it.first } }
    val formats = remember(all) { all.groupBy { it.format }.toList().sortedByDescending { it.second.size } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nav_shelves)) }, actions = { IconButton(onClick = { create = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.new_shelf)) } })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (shelves.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(Illustration.SHELF, stringResource(R.string.shelves_empty_title), stringResource(R.string.shelves_empty_body)) {
                            TextButton(onClick = { create = true }) { Text(stringResource(R.string.new_shelf)) }
                        }
                    }
                }
            }
            items(shelves, key = { it.collection.id }) { s ->
                ShelfCard(s.collection, s.count, s.coverPath?.let { p -> all.firstOrNull { it.coverPath == p } }, vm) { actions.navigate(ShelfKey(s.collection.id)) }
            }
            if (authors.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.authors)) }
                item(span = { GridItemSpan(maxLineSpan) }) { ChipFlow(authors.take(40).map { it.first to it.second.size }) { actions.navigate(GroupKey("author", it)) } }
            }
            if (series.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.series)) }
                item(span = { GridItemSpan(maxLineSpan) }) { ChipFlow(series.map { it.first to it.second.size }) { actions.navigate(GroupKey("series", it)) } }
            }
            if (formats.size > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.formats)) }
                item(span = { GridItemSpan(maxLineSpan) }) { ChipFlow(formats.map { BookFormat.byName(it.first).label to it.second.size }) { label -> actions.navigate(GroupKey("format", BookFormat.entries.first { f -> f.label == label }.name)) } }
            }
        }
    }
    if (create) {
        NameDialog(stringResource(R.string.new_shelf), "") { name ->
            create = false
            if (name != null) vm.createCollection(name)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<Pair<String, Int>>, onClick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, count) ->
            AssistChip(onClick = { onClick(label) }, label = { Text("$label · $count", maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

@Composable
private fun ShelfCard(c: CollectionEntity, count: Int, coverBook: BookEntity?, vm: LibraryViewModel, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.clip(RoundedCornerShape(22.dp)).combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).padding(top = 16.dp), contentAlignment = Alignment.Center) {
            if (coverBook != null) {
                Box(Modifier.width(64.dp).aspectRatio(0.68f).offset(x = (-24).dp).rotate(-9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiaryContainer))
                Box(Modifier.width(66.dp).aspectRatio(0.68f).offset(x = 22.dp).rotate(7f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer))
                Box(Modifier.width(70.dp)) { BookCover(coverBook, Modifier.fillMaxWidth(), elevation = 8.dp) }
            } else {
                com.readarea.ui.components.Art(Illustration.SHELF, Modifier.fillMaxSize())
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.book_count, count, count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.shelf_options)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_shelf)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; delete = true })
                }
            }
        }
    }
    if (rename) NameDialog(stringResource(R.string.rename_shelf), c.name) { n ->
        rename = false
        if (n != null) vm.renameCollection(c, n)
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text(stringResource(R.string.delete_shelf_title, c.name)) },
            text = { Text(stringResource(R.string.delete_shelf_body)) },
            confirmButton = { TextButton(onClick = { delete = false; vm.deleteCollection(c.id) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ShelfScreen(vm: LibraryViewModel, actions: AppActions, id: Long) {
    val shelf by remember(id) { vm.collection(id) }.collectAsStateWithLifecycle(null)
    val books by remember(id) { vm.collectionBooks(id) }.collectAsStateWithLifecycle(emptyList())
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(shelf?.name ?: "") },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_shelf)) }, onClick = {
                                menu = false
                                vm.deleteCollection(id)
                                actions.back()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Illustration.SHELF, stringResource(R.string.shelf_empty_title), stringResource(R.string.shelf_empty_body))
            }
            return@Scaffold
        }
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            items(books, key = { it.id }) { b ->
                var itemMenu by remember { mutableStateOf(false) }
                Box {
                    BookListItem(b, false, onClick = { actions.openBook(b.id) }, onLongClick = { itemMenu = true })
                    DropdownMenu(itemMenu, { itemMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.details)) }, onClick = { itemMenu = false; actions.showDetails(b.id) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_shelf)) }, onClick = { itemMenu = false; vm.removeFromCollection(id, listOf(b.id)) })
                    }
                }
            }
        }
    }
    val s = shelf
    if (rename && s != null) NameDialog(stringResource(R.string.rename_shelf), s.name) { n ->
        rename = false
        if (n != null) vm.renameCollection(s, n)
    }
}

@Composable
fun GroupScreen(vm: LibraryViewModel, actions: AppActions, kind: String, value: String) {
    val all by vm.allBooks.collectAsStateWithLifecycle()
    val books = remember(all, kind, value) {
        all.orEmpty().filter {
            when (kind) {
                "author" -> it.author == value
                "series" -> it.series == value
                else -> it.format == value
            }
        }.sortedWith(compareBy({ it.seriesIndex ?: Float.MAX_VALUE }, { it.title }))
    }
    val title = if (kind == "format") BookFormat.byName(value).label else value
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.book_count, books.size, books.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            items(books, key = { it.id }) { b ->
                BookListItem(b, false, onClick = { actions.openBook(b.id) }, onLongClick = { actions.showDetails(b.id) })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
