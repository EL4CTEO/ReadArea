package com.readarea.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookStatus
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryFilter
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.components.BookGridItem
import com.readarea.ui.components.BookListItem
import com.readarea.ui.components.EmptyState
import com.readarea.ui.components.Illustration

val sortOptions = listOf("recent" to "Recently read", "added" to "Date added", "title" to "Title", "author" to "Author & series", "progress" to "Progress", "size" to "File size")

@Composable
fun LibraryScreen(vm: LibraryViewModel, actions: AppActions) {
    val books by vm.library.collectAsStateWithLifecycle()
    val all by vm.allBooks.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val formats by vm.formats.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var searching by remember { mutableStateOf(q.text.isNotEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var shelfDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = selection.isNotEmpty() || searching) {
        if (selection.isNotEmpty()) selection = emptySet() else {
            searching = false
            vm.setQuery(q.copy(text = ""))
        }
    }
    val toggle: (Long) -> Unit = { id -> selection = if (id in selection) selection - id else selection + id }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    title = { Text("${selection.size} selected") },
                    actions = {
                        if (selection.size == 1) IconButton(onClick = { actions.showDetails(selection.first()); selection = emptySet() }) { Icon(Icons.Rounded.Info, "Details") }
                        IconButton(onClick = { selection = books.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                        IconButton(onClick = {
                            val allFav = books.filter { it.id in selection }.all { it.favorite }
                            vm.setFavorite(selection.toList(), !allFav)
                        }) { Icon(Icons.Rounded.Favorite, "Favorite") }
                        IconButton(onClick = { shelfDialog = true }) { Icon(Icons.Rounded.CollectionsBookmark, "Add to shelf") }
                        Box {
                            IconButton(onClick = { statusMenu = true }) { Icon(Icons.Rounded.Check, "Mark as") }
                            DropdownMenu(statusMenu, { statusMenu = false }) {
                                listOf(BookStatus.WANT to "Want to read", BookStatus.READING to "Reading", BookStatus.FINISHED to "Finished").forEach { (st, label) ->
                                    DropdownMenuItem(text = { Text("Mark as $label") }, onClick = {
                                        vm.setStatus(selection.toList(), st)
                                        statusMenu = false
                                        selection = emptySet()
                                    })
                                }
                                DropdownMenuItem(text = { Text("Reset progress") }, onClick = {
                                    vm.resetProgress(selection.toList())
                                    statusMenu = false
                                    selection = emptySet()
                                })
                            }
                        }
                        IconButton(onClick = { removeDialog = true }) { Icon(Icons.Rounded.Delete, "Remove") }
                    },
                )
            } else if (searching) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { searching = false; vm.setQuery(q.copy(text = "")) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                    title = {
                        TextField(
                            value = q.text,
                            onValueChange = { vm.setQuery(q.copy(text = it)) },
                            placeholder = { Text("Title, author or series") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    actions = { if (q.text.isNotEmpty()) IconButton(onClick = { vm.setQuery(q.copy(text = "")) }) { Icon(Icons.Rounded.Close, "Clear") } },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Library")
                            all?.let { Text("${it.size} books", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                sortOptions.forEach { (k, l) ->
                                    DropdownMenuItem(
                                        text = { Text(l) },
                                        trailingIcon = { if (s.sort == k) Icon(Icons.Rounded.Check, null) },
                                        onClick = {
                                            vm.updateSettings { it.copy(sort = k) }
                                            sortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { vm.updateSettings { it.copy(libraryGrid = !it.libraryGrid) } }) {
                            Icon(if (s.libraryGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "Change view")
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(moreMenu, { moreMenu = false }) {
                                DropdownMenuItem(text = { Text("Rescan folders") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { moreMenu = false; vm.rescan() })
                                listOf(2 to "Large covers", 3 to "Medium covers", 4 to "Small covers").forEach { (c, l) ->
                                    DropdownMenuItem(text = { Text(l) }, trailingIcon = { if (s.gridColumns == c) Icon(Icons.Rounded.Check, null) }, onClick = { moreMenu = false; vm.updateSettings { it.copy(gridColumns = c, libraryGrid = true) } })
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                Box {
                    ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add books") })
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Add a folder") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { addMenu = false; actions.addFolder() })
                        DropdownMenuItem(text = { Text("Open files") }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) }, onClick = { addMenu = false; actions.importFiles() })
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = scan.running && scan.message?.startsWith("Scanning") == true, onRefresh = vm::rescan, modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.fillMaxSize()) {
                if (scan.running) {
                    val p = if (scan.total > 0) scan.processed.toFloat() / scan.total else null
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryFilter.entries.forEach { f ->
                        FilterChip(selected = q.filter == f, onClick = { vm.setQuery(q.copy(filter = f)) }, label = { Text(f.label) })
                    }
                    if (formats.size > 1) {
                        formats.forEach { f ->
                            FilterChip(selected = q.format == f, onClick = { vm.setQuery(q.copy(format = if (q.format == f) null else f)) }, label = { Text(BookFormat.byName(f).label) })
                        }
                    }
                }
                if (books.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (all.isNullOrEmpty()) {
                            EmptyState(Illustration.BOOKS, "Your library is empty", "Add a folder and ReadArea will find every book inside it, including subfolders.") {
                                TextButton(onClick = actions.addFolder) { Text("Add a folder") }
                            }
                        } else {
                            EmptyState(Illustration.SEARCH, "Nothing matches", "Try a different search or filter.")
                        }
                    }
                } else if (s.libraryGrid) {
                    val min = when (s.gridColumns) {
                        2 -> 150.dp
                        4 -> 86.dp
                        else -> 108.dp
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(min),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(books, key = { it.id }) { b ->
                            BookGridItem(
                                b,
                                selected = b.id in selection,
                                onClick = { if (selection.isNotEmpty()) toggle(b.id) else actions.openBook(b.id) },
                                onLongClick = { toggle(b.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                        items(books, key = { it.id }) { b ->
                            Box(Modifier.animateItem()) {
                                BookListItem(
                                    b,
                                    selected = b.id in selection,
                                    onClick = { if (selection.isNotEmpty()) toggle(b.id) else actions.openBook(b.id) },
                                    onLongClick = { toggle(b.id) },
                                    showBadge = s.showFormatBadges,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (shelfDialog) {
        AddToShelfDialog(vm, selection.toList()) {
            shelfDialog = false
            selection = emptySet()
        }
    }
    if (removeDialog) {
        RemoveDialog(selection.size, onDismiss = { removeDialog = false }) { deleteFiles ->
            vm.remove(selection.toList(), deleteFiles)
            removeDialog = false
            selection = emptySet()
        }
    }
}

@Composable
fun AddToShelfDialog(vm: LibraryViewModel, bookIds: List<Long>, onDismiss: () -> Unit) {
    val shelves by vm.collections.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to shelf") },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(shelves, key = { it.collection.id }) { c ->
                        TextButton(onClick = {
                            vm.addToCollection(c.collection.id, bookIds)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(c.collection.name, modifier = Modifier.weight(1f))
                            Text("${c.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("New shelf") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                vm.createCollection(name, bookIds)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RemoveDialog(count: Int, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Remove book?" else "Remove $count books?") },
        text = {
            Column {
                Text("Reading progress, notes and highlights for ${if (count == 1) "this book" else "these books"} will be removed.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    androidx.compose.material3.Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text("Also delete the file${if (count == 1) "" else "s"} from the device")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
