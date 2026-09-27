package com.readarea.ui.settings

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readarea.app
import com.readarea.data.ReaderSettings
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.components.formatSize
import com.readarea.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: LibraryViewModel, actions: AppActions) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<String?>(null) }
    var cache by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cache = withContext(Dispatchers.IO) { vm.cacheSize() } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                Group("Appearance")
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val opts = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                    opts.forEachIndexed { i, (k, l) ->
                        SegmentedButton(selected = s.themeMode == k, onClick = { vm.updateSettings { it.copy(themeMode = k) } }, shape = SegmentedButtonDefaults.itemShape(i, opts.size), label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Accent", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Accents.colors.forEachIndexed { i, c ->
                        val selected = s.accent == i && !s.dynamicColor
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { vm.updateSettings { it.copy(accent = i, dynamicColor = false) } },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Rounded.Check, Accents.names[i], tint = Color.White) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    Toggle("Use wallpaper colors", "Material You dynamic color", s.dynamicColor) { v -> vm.updateSettings { it.copy(dynamicColor = v) } }
                }
                Toggle("Format badges", "Show EPUB, PDF… labels in list view", s.showFormatBadges) { v -> vm.updateSettings { it.copy(showFormatBadges = v) } }

                Group("Library folders")
                Text("ReadArea watches these folders and adds new books automatically. Only the folders you pick are accessible — no broad storage permission needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                s.folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(folderName(f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { removing = f }) { Icon(Icons.Rounded.Delete, "Remove folder") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = actions.addFolder) {
                        Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add folder")
                    }
                    OutlinedButton(onClick = vm::rescan, enabled = !scan.running && s.folders.isNotEmpty()) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (scan.running) "Scanning…" else "Rescan")
                    }
                }

                Group("Reading")
                Text("Fonts, themes, brightness, page-turn animation and more live inside a book — tap the center of a page to open the menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { context.app.settings.updateReader { ReaderSettings() } } }) { Text("Reset all reading settings") }

                Group("Storage")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Book cache", style = MaterialTheme.typography.bodyLarge)
                        Text("Temporary copies used for fast opening · ${formatSize(cache)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.clearCache(); cache = 0 }) { Text("Clear") }
                }

                Group("About")
                Text("ReadArea ${appVersion(context)}", style = MaterialTheme.typography.bodyLarge)
                Text("No ads, no accounts, no tracking. ReadArea doesn't even have internet access — your books and notes never leave your device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove folder?") },
            text = { Text("Stop watching “${folderName(f)}”. Do you also want to remove its books from your library?") },
            confirmButton = { TextButton(onClick = { vm.removeFolder(f, true); removing = null }) { Text("Remove books too") } },
            dismissButton = { TextButton(onClick = { vm.removeFolder(f, false); removing = null }) { Text("Keep books") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun folderName(uri: String): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(uri.toUri())
    val path = id.substringAfter(':', id)
    val root = id.substringBefore(':')
    (if (root.equals("primary", true)) "Internal storage" else root) + if (path.isNotEmpty()) " › " + path.replace("/", " › ") else ""
}.getOrElse { Uri.decode(uri) }

private fun appVersion(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}.getOrDefault("")
