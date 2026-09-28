package com.readarea.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.TravelExplore
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.core.format.BookFormat
import com.readarea.data.db.BookStatus
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryFilter
import com.readarea.ui.LibraryViewModel
import com.readarea.data.ScanPhase
import com.readarea.ui.components.BookGridItem
import com.readarea.ui.components.BookListItem
import com.readarea.ui.components.EmptyState
import com.readarea.ui.components.Illustration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.readarea.R

val sortOptions = listOf("recent" to R.string.sort_recent, "added" to R.string.sort_added, "title" to R.string.sort_title, "author" to R.string.sort_author, "progress" to R.string.sort_progress, "size" to R.string.sort_size)

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
    // A filter or a rescan can take selected books off the screen. They must not stay selected unseen: the bar would
    // still count them and "Remove" would still reach them.
    LaunchedEffect(books) {
        if (selection.isNotEmpty()) {
            val shown = books.mapTo(HashSet()) { it.id }
            if (!shown.containsAll(selection)) selection = selection.filterTo(HashSet()) { it in shown }
        }
    }
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
                SelectionBar(
                    count = selection.size,
                    onClear = { selection = emptySet() },
                    onSelectAll = { selection = books.map { it.id }.toSet() },
                    onDetails = if (selection.size == 1) ({ actions.showDetails(selection.first()); selection = emptySet() }) else null,
                    onFavorite = {
                        val allFav = books.filter { it.id in selection }.all { it.favorite }
                        vm.setFavorite(selection.toList(), !allFav)
                    },
                    onShelf = { shelfDialog = true },
                    onStatus = { status ->
                        vm.setStatus(selection.toList(), status)
                        selection = emptySet()
                    },
                    onResetProgress = {
                        vm.resetProgress(selection.toList())
                        selection = emptySet()
                    },
                    onRemove = { removeDialog = true },
                )
            } else if (searching) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { searching = false; vm.setQuery(q.copy(text = "")) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.close_search)) } },
                    title = {
                        TextField(
                            value = q.text,
                            onValueChange = { vm.setQuery(q.copy(text = it)) },
                            placeholder = { Text(stringResource(R.string.search_library_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    actions = { if (q.text.isNotEmpty()) IconButton(onClick = { vm.setQuery(q.copy(text = "")) }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) } },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.nav_library))
                            all?.let { Text(pluralStringResource(R.plurals.book_count, it.size, it.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, stringResource(R.string.search)) }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort)) }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                sortOptions.forEach { (k, l) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(l)) },
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
                            Icon(if (s.libraryGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, stringResource(R.string.change_view))
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(moreMenu, { moreMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rescan_folders)) }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { moreMenu = false; vm.rescan() })
                                listOf(2 to R.string.covers_large, 3 to R.string.covers_medium, 4 to R.string.covers_small).forEach { (c, l) ->
                                    DropdownMenuItem(text = { Text(stringResource(l)) }, trailingIcon = { if (s.gridColumns == c) Icon(Icons.Rounded.Check, null) }, onClick = { moreMenu = false; vm.updateSettings { it.copy(gridColumns = c, libraryGrid = true) } })
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
                    ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.add_books)) })
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.find_books)) }, leadingIcon = { Icon(Icons.Rounded.TravelExplore, null) }, onClick = { addMenu = false; actions.findBooks() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_folder)) }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { addMenu = false; actions.addFolder() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_files)) }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) }, onClick = { addMenu = false; actions.importFiles() })
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = scan.running && scan.phase == ScanPhase.FOLDERS, onRefresh = vm::rescan, modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(Modifier.fillMaxSize()) {
                if (scan.running) {
                    val p = if (scan.total > 0) scan.processed.toFloat() / scan.total else null
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryFilter.entries.forEach { f ->
                        FilterChip(selected = q.filter == f, onClick = { vm.setQuery(q.copy(filter = f)) }, label = { Text(stringResource(f.label)) })
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
                            EmptyState(Illustration.BOOKS, stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body)) {
                                Button(onClick = actions.findBooks) { Text(stringResource(R.string.find_books)) }
                                TextButton(onClick = actions.addFolder) { Text(stringResource(R.string.add_folder)) }
                            }
                        } else {
                            EmptyState(Illustration.SEARCH, stringResource(R.string.no_matches_title), stringResource(R.string.no_matches_body))
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

/** Bars narrower than this keep two actions in view and move the rest into the menu, so the count stays readable. */
private val selectionWideMinWidth = 560.dp

/**
 * The bar shown while books are selected. Every icon button takes 48dp, and a phone is only 320 to 430dp wide: with a
 * close button and six actions the count was left a few dp and wrapped letter by letter. So a narrow bar shows
 * select all and add to shelf, and keeps the other actions in the ⋮ menu; a wide one shows them all.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDetails: (() -> Unit)?,
    onFavorite: () -> Unit,
    onShelf: () -> Unit,
    onStatus: (Int) -> Unit,
    onResetProgress: () -> Unit,
    onRemove: () -> Unit,
) {
    var markMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    BoxWithConstraints {
        val wide = maxWidth >= selectionWideMinWidth
        TopAppBar(
            navigationIcon = { IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, stringResource(R.string.clear_selection)) } },
            // One line whatever the language: a count too long for the room is cut short, not wrapped.
            title = { Text(pluralStringResource(R.plurals.selected_count, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                if (wide && onDetails != null) IconButton(onClick = onDetails) { Icon(Icons.Rounded.Info, stringResource(R.string.details)) }
                IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
                if (wide) IconButton(onClick = onFavorite) { Icon(Icons.Rounded.Favorite, stringResource(R.string.favorite)) }
                IconButton(onClick = onShelf) { Icon(Icons.Rounded.CollectionsBookmark, stringResource(R.string.add_to_shelf)) }
                if (wide) {
                    Box {
                        IconButton(onClick = { markMenu = true }) { Icon(Icons.Rounded.Check, stringResource(R.string.mark_as)) }
                        DropdownMenu(markMenu, { markMenu = false }) {
                            MarkAsItems(onStatus = { markMenu = false; onStatus(it) }, onResetProgress = { markMenu = false; onResetProgress() })
                        }
                    }
                    IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, stringResource(R.string.remove)) }
                } else {
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            if (onDetails != null) DropdownMenuItem(text = { Text(stringResource(R.string.details)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { moreMenu = false; onDetails() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.favorite)) }, leadingIcon = { Icon(Icons.Rounded.Favorite, null) }, onClick = { moreMenu = false; onFavorite() })
                            HorizontalDivider()
                            MarkAsItems(onStatus = { moreMenu = false; onStatus(it) }, onResetProgress = { moreMenu = false; onResetProgress() })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { moreMenu = false; onRemove() },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MarkAsItems(onStatus: (Int) -> Unit, onResetProgress: () -> Unit) {
    listOf(BookStatus.WANT to R.string.mark_want, BookStatus.READING to R.string.mark_reading, BookStatus.FINISHED to R.string.mark_finished).forEach { (status, label) ->
        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onStatus(status) })
    }
    DropdownMenuItem(text = { Text(stringResource(R.string.reset_progress)) }, onClick = onResetProgress)
}

@Composable
fun AddToShelfDialog(vm: LibraryViewModel, bookIds: List<Long>, onDismiss: () -> Unit) {
    val shelves by vm.collections.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_shelf)) },
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
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.new_shelf)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                vm.createCollection(name, bookIds)
                onDismiss()
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun RemoveDialog(count: Int, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.remove_books_title, count, count)) },
        text = {
            Column {
                Text(pluralStringResource(R.plurals.remove_books_body, count, count))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).toggleable(deleteFiles, role = Role.Checkbox) { deleteFiles = it }.padding(end = 8.dp),
                ) {
                    androidx.compose.material3.Checkbox(checked = deleteFiles, onCheckedChange = null)
                    Text(pluralStringResource(R.plurals.delete_files_too, count, count))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles) }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
