package com.readarea.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.readarea.data.DeviceStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.readarea.reader.ReaderActivity
import com.readarea.ui.home.HomeScreen
import com.readarea.ui.library.BookDetailsSheet
import com.readarea.ui.library.LibraryScreen
import com.readarea.ui.notes.NotesScreen
import com.readarea.ui.settings.SettingsScreen
import com.readarea.ui.shelves.GroupScreen
import com.readarea.ui.shelves.ShelfScreen
import com.readarea.ui.shelves.ShelvesScreen
import com.readarea.ui.stats.StatsScreen
import kotlinx.serialization.Serializable
import androidx.compose.ui.res.stringResource
import com.readarea.R

@Serializable data object HomeKey : NavKey
@Serializable data object LibraryKey : NavKey
@Serializable data object ShelvesKey : NavKey
@Serializable data object NotesKey : NavKey
@Serializable data object StatsKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data class ShelfKey(val id: Long) : NavKey
@Serializable data class GroupKey(val kind: String, val value: String) : NavKey

private data class Destination(val key: NavKey, val label: Int, val icon: ImageVector)

private val destinations = listOf(
    Destination(HomeKey, R.string.nav_home, Icons.Rounded.Home),
    Destination(LibraryKey, R.string.nav_library, Icons.AutoMirrored.Rounded.LibraryBooks),
    Destination(ShelvesKey, R.string.nav_shelves, Icons.Rounded.CollectionsBookmark),
    Destination(NotesKey, R.string.nav_notes, Icons.AutoMirrored.Rounded.StickyNote2),
    Destination(StatsKey, R.string.nav_stats, Icons.Rounded.BarChart),
)

class AppActions(
    val openBook: (Long) -> Unit,
    val showDetails: (Long) -> Unit,
    val navigate: (NavKey) -> Unit,
    val back: () -> Unit,
    val addFolder: () -> Unit,
    val importFiles: () -> Unit,
    val findBooks: () -> Unit = {},
)

val bookMimeTypes = arrayOf(
    "application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
    "application/x-fictionbook+xml", "application/x-fictionbook", "text/plain", "text/html", "application/xhtml+xml",
    "text/markdown", "text/x-markdown", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf", "application/vnd.comicbook+zip",
    "application/x-cbz", "application/zip", "application/octet-stream",
)

fun openReader(context: Context, id: Long) {
    context.startActivity(ReaderActivity.intent(context, id))
}

@Composable
fun ReadAreaRoot(vm: LibraryViewModel) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(HomeKey)
    var details by rememberSaveable { mutableStateOf<Long?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let { vm.addFolder(it) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importFiles(uris) { ids -> if (ids.size == 1) openReader(context, ids[0]) }
    }
    var findDialog by rememberSaveable { mutableStateOf(false) }
    val accessSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (DeviceStorage.hasAccess(context)) vm.setDeviceScan(true) else vm.markDeviceScanAsked()
    }
    val accessPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setDeviceScan(true) else vm.markDeviceScanAsked()
    }
    LaunchedEffect(Unit) {
        if (vm.shouldOfferDeviceScan()) {
            if (DeviceStorage.hasAccess(context)) vm.setDeviceScan(true) else findDialog = true
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.adoptGrantedAccess()
        onPauseOrDispose {}
    }
    if (findDialog) {
        AlertDialog(
            onDismissRequest = { findDialog = false; vm.markDeviceScanAsked() },
            icon = { Icon(Icons.Rounded.TravelExplore, null) },
            title = { Text(stringResource(R.string.find_books_title)) },
            text = { Text(stringResource(R.string.find_books_body)) },
            confirmButton = {
                Button(onClick = {
                    findDialog = false
                    if (DeviceStorage.needsSettingsScreen) DeviceStorage.accessIntents(context).firstOrNull { runCatching { accessSettings.launch(it) }.isSuccess }
                    else accessPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = { TextButton(onClick = { findDialog = false; vm.markDeviceScanAsked() }) { Text(stringResource(R.string.not_now)) } },
        )
    }
    val actions = remember(backStack) {
        AppActions(
            openBook = { openReader(context, it) },
            showDetails = { details = it },
            navigate = { key ->
                if (destinations.any { it.key == key }) {
                    backStack.clear()
                    backStack.add(HomeKey)
                    if (key != HomeKey) backStack.add(key)
                } else backStack.add(key)
            },
            back = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            addFolder = { folderPicker.launch(null) },
            importFiles = { filePicker.launch(bookMimeTypes) },
            findBooks = { if (DeviceStorage.hasAccess(context)) vm.setDeviceScan(true) else findDialog = true },
        )
    }
    val top = backStack.lastOrNull()
    val currentTab = backStack.lastOrNull { k -> destinations.any { it.key == k } } ?: HomeKey
    val adaptive = currentWindowAdaptiveInfo()
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            destinations.forEach { d ->
                item(
                    selected = currentTab == d.key && top != SettingsKey,
                    onClick = { actions.navigate(d.key) },
                    icon = { Icon(d.icon, stringResource(d.label)) },
                    label = { Text(stringResource(d.label)) },
                )
            }
            if (layoutType != NavigationSuiteType.NavigationBar) {
                item(
                    selected = top == SettingsKey,
                    onClick = { if (top != SettingsKey) backStack.add(SettingsKey) },
                    icon = { Icon(Icons.Rounded.Settings, stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        layoutType = layoutType,
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { actions.back() },
            entryProvider = entryProvider {
                entry<HomeKey> { HomeScreen(vm, actions) }
                entry<LibraryKey> { LibraryScreen(vm, actions) }
                entry<ShelvesKey> { ShelvesScreen(vm, actions) }
                entry<NotesKey> { NotesScreen(vm, actions) }
                entry<StatsKey> { StatsScreen(vm, actions) }
                entry<SettingsKey> { SettingsScreen(vm, actions) }
                entry<ShelfKey> { key -> ShelfScreen(vm, actions, key.id) }
                entry<GroupKey> { key -> GroupScreen(vm, actions, key.kind, key.value) }
            },
        )
    }
    details?.let { id -> BookDetailsSheet(vm, actions, id) { details = null } }
}
