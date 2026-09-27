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
import com.readarea.AppLanguage
import androidx.compose.material.icons.rounded.Translate
import com.readarea.data.ReaderSettings
import com.readarea.ui.AppActions
import com.readarea.ui.LibraryViewModel
import com.readarea.ui.components.formatSize
import com.readarea.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.readarea.R

@Composable
fun SettingsScreen(vm: LibraryViewModel, actions: AppActions) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<String?>(null) }
    var stopDevice by remember { mutableStateOf(false) }
    var cache by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cache = withContext(Dispatchers.IO) { vm.cacheSize() } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.nav_settings)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                Group(stringResource(R.string.appearance))
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val opts = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
                    opts.forEachIndexed { i, (k, l) ->
                        SegmentedButton(selected = s.themeMode == k, onClick = { vm.updateSettings { it.copy(themeMode = k) } }, shape = SegmentedButtonDefaults.itemShape(i, opts.size), label = { Text(stringResource(l)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.accent), style = MaterialTheme.typography.bodyLarge)
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
                    Toggle(stringResource(R.string.wallpaper_colors), stringResource(R.string.wallpaper_colors_hint), s.dynamicColor) { v -> vm.updateSettings { it.copy(dynamicColor = v) } }
                }
                Toggle(stringResource(R.string.format_badges), stringResource(R.string.format_badges_hint), s.showFormatBadges) { v -> vm.updateSettings { it.copy(showFormatBadges = v) } }

                Group(stringResource(R.string.language))
                val activity = context as? android.app.Activity
                var langDialog by remember { mutableStateOf(false) }
                val currentLang = remember(langDialog) { AppLanguage.current(context) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { langDialog = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Translate, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_language), style = MaterialTheme.typography.bodyLarge)
                        Text(if (currentLang.isEmpty()) stringResource(R.string.system_default) else AppLanguage.displayName(currentLang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (langDialog) {
                    AlertDialog(
                        onDismissRequest = { langDialog = false },
                        title = { Text(stringResource(R.string.app_language)) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                (listOf("") + AppLanguage.tags).forEach { tag ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                            langDialog = false
                                            activity?.let { AppLanguage.set(it, tag) }
                                        }.padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.RadioButton(selected = currentLang == tag || (tag.isNotEmpty() && currentLang.startsWith(tag)), onClick = null)
                                        Spacer(Modifier.width(12.dp))
                                        Text(if (tag.isEmpty()) stringResource(R.string.system_default) else AppLanguage.displayName(tag), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { langDialog = false }) { Text(stringResource(R.string.cancel)) } },
                    )
                }

                Group(stringResource(R.string.library_folders))
                Text(stringResource(R.string.library_folders_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val deviceOn = s.deviceScan && com.readarea.data.DeviceStorage.hasAccess(context)
                Toggle(stringResource(R.string.device_scan), stringResource(R.string.device_scan_hint), deviceOn) { on -> if (on) actions.findBooks() else stopDevice = true }
                s.folders.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(folderName(context, f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { removing = f }) { Icon(Icons.Rounded.Delete, stringResource(R.string.remove_folder)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = actions.addFolder) {
                        Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_folder))
                    }
                    OutlinedButton(onClick = vm::rescan, enabled = !scan.running && (s.folders.isNotEmpty() || s.deviceScan)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (scan.running) R.string.scanning else R.string.rescan))
                    }
                }

                Group(stringResource(R.string.reading))
                Toggle(stringResource(R.string.reopen_last), stringResource(R.string.reopen_last_hint), s.reopenLastBook) { v -> vm.updateSettings { it.copy(reopenLastBook = v) } }
                Text(stringResource(R.string.reading_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { context.app.settings.updateReader { ReaderSettings() } } }) { Text(stringResource(R.string.reset_reading_settings)) }

                Group(stringResource(R.string.storage))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.book_cache), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.book_cache_hint, formatSize(cache)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.clearCache(); cache = 0 }) { Text(stringResource(R.string.clear)) }
                }

                Group(stringResource(R.string.about))
                Text("ReadArea ${appVersion(context)}", style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (stopDevice) {
        AlertDialog(
            onDismissRequest = { stopDevice = false },
            title = { Text(stringResource(R.string.device_scan)) },
            text = { Text(stringResource(R.string.remove_folder_body, stringResource(R.string.this_device))) },
            confirmButton = { TextButton(onClick = { vm.setDeviceScan(false, true); stopDevice = false }) { Text(stringResource(R.string.remove_books_too)) } },
            dismissButton = { TextButton(onClick = { vm.setDeviceScan(false, false); stopDevice = false }) { Text(stringResource(R.string.keep_books)) } },
        )
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_folder_title)) },
            text = { Text(stringResource(R.string.remove_folder_body, folderName(context, f))) },
            confirmButton = { TextButton(onClick = { vm.removeFolder(f, true); removing = null }) { Text(stringResource(R.string.remove_books_too)) } },
            dismissButton = { TextButton(onClick = { vm.removeFolder(f, false); removing = null }) { Text(stringResource(R.string.keep_books)) } },
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

private fun folderName(context: android.content.Context, uri: String): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(uri.toUri())
    val path = id.substringAfter(':', id)
    val root = id.substringBefore(':')
    (if (root.equals("primary", true)) context.getString(R.string.internal_storage) else root) + if (path.isNotEmpty()) " › " + path.replace("/", " › ") else ""
}.getOrElse { Uri.decode(uri) }

private fun appVersion(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}.getOrDefault("")
