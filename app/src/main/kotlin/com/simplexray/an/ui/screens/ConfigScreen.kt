package com.simplexray.an.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.simplexray.an.R
import com.simplexray.an.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Deflater

private const val TAG = "ConfigScreen"

private data class PendingExport(val content: String)

private fun generateSimpleXrayShareLink(name: String, content: String): String {
    val input = content.toByteArray(Charsets.UTF_8)
    val outputStream = ByteArrayOutputStream()
    val deflater = Deflater()
    val buffer = ByteArray(1024)
    try {
        deflater.setInput(input)
        deflater.finish()
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            outputStream.write(buffer, 0, count)
        }
    } finally {
        deflater.end()
    }
    val compressed = outputStream.toByteArray()
    val encodedContent = Base64.getUrlEncoder().encodeToString(compressed)
    val encodedName = URLEncoder.encode(name, "UTF-8")
    return "simplexray://config/$encodedName/$encodedContent"
}

private fun encodeUrlComponent(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun generateVlessShareLink(name: String, content: String): String? {
    return runCatching {
        val root = JSONObject(content)
        val outbounds = root.optJSONArray("outbounds") ?: return null
        if (outbounds.length() == 0) return null
        val outbound = outbounds.optJSONObject(0) ?: return null
        if (outbound.optString("protocol") != "vless") return null
        val settings = outbound.optJSONObject("settings") ?: return null
        val vnextArray = settings.optJSONArray("vnext") ?: return null
        val vnext = vnextArray.optJSONObject(0) ?: return null
        val address = vnext.optString("address")
        val port = vnext.optInt("port", -1)
        if (address.isBlank() || port !in 1..65535) return null
        if (address.contains("@") || address.contains("/") || address.contains("?") || address.contains("#")) {
            return null
        }
        val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
        val id = user.optString("id")
        if (id.isBlank()) return null

        val queryParams = linkedMapOf<String, String>()
        user.optString("flow").takeIf { it.isNotBlank() }?.also { queryParams["flow"] = it }

        outbound.optJSONObject("streamSettings")?.let { streamSettings ->
            streamSettings.optString("network").takeIf { it.isNotBlank() }
                ?.also { queryParams["type"] = it }
            streamSettings.optString("security").takeIf { it.isNotBlank() }
                ?.also { queryParams["security"] = it }

            streamSettings.optJSONObject("realitySettings")?.let { reality ->
                reality.optString("serverName").takeIf { it.isNotBlank() }
                    ?.also { queryParams["sni"] = it }
                reality.optString("fingerprint").takeIf { it.isNotBlank() }
                    ?.also { queryParams["fp"] = it }
                reality.optString("publicKey").takeIf { it.isNotBlank() }
                    ?.also { queryParams["pbk"] = it }
                reality.optString("shortId").takeIf { it.isNotBlank() }
                    ?.also { queryParams["sid"] = it }
                reality.optString("spiderX").takeIf { it.isNotBlank() }
                    ?.also { queryParams["spx"] = it }
            }
        }

        val query = queryParams.entries.joinToString("&") { (k, v) ->
            "${encodeUrlComponent(k)}=${encodeUrlComponent(v)}"
        }
        val host = if (address.contains(":") && !address.startsWith("[") && !address.endsWith("]")) {
            "[$address]"
        } else {
            address
        }
        val base = "vless://${encodeUrlComponent(id)}@$host:$port"
        val fragment = encodeUrlComponent(name)
        if (query.isBlank()) "$base#$fragment" else "$base?$query#$fragment"
    }.getOrNull()
}

@Composable
fun ConfigScreen(
    onReloadConfig: () -> Unit,
    onEditConfigClick: (File) -> Unit,
    onDeleteConfigClick: (File, () -> Unit) -> Unit,
    mainViewModel: MainViewModel,
    listState: LazyListState
) {
    val showDeleteDialog = remember { mutableStateOf<File?>(null) }
    var shareMenuFile by remember { mutableStateOf<File?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isServiceEnabled by mainViewModel.isServiceEnabled.collectAsState()

    val files by mainViewModel.configFiles.collectAsState()
    val selectedFile by mainViewModel.selectedConfigFile.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshConfigFileList()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        mainViewModel.refreshConfigFileList()
    }

    val hapticFeedback = LocalHapticFeedback.current
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        mainViewModel.moveConfigFile(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val currentPendingExport = pendingExport
        if (uri == null || currentPendingExport == null) {
            Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            pendingExport = null
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val success = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(currentPendingExport.content.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(if (success) R.string.export_success else R.string.export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                pendingExport = null
            }
        }
    }

    fun copyTextToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_config_files),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 10.dp, top = 10.dp),
                state = listState
            ) {
                items(files, key = { it }) { file ->
                    ReorderableItem(reorderableLazyListState, key = file) {
                        val isSelected = file == selectedFile
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(MaterialTheme.shapes.extraLarge)
                                .clickable {
                                    mainViewModel.updateSelectedConfigFile(file)
                                    if (isServiceEnabled) {
                                        Log.d(
                                            TAG,
                                            "Config selected while service is running, requesting reload."
                                        )
                                        onReloadConfig()
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max)
                                    .longPressDraggableHandle(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        file.name.removeSuffix(".json"),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    IconButton(onClick = { onEditConfigClick(file) }) {
                                        Icon(
                                            painterResource(R.drawable.edit),
                                            contentDescription = "Edit"
                                        )
                                    }
                                    IconButton(onClick = { shareMenuFile = file }) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = stringResource(R.string.share)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = shareMenuFile == file,
                                        onDismissRequest = { shareMenuFile = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.copy_netx_config)) },
                                            onClick = {
                                                shareMenuFile = null
                                                scope.launch(Dispatchers.IO) {
                                                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                                                    withContext(Dispatchers.Main) {
                                                        if (content == null) {
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(R.string.export_failed),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } else {
                                                            copyTextToClipboard(
                                                                file.nameWithoutExtension,
                                                                generateSimpleXrayShareLink(file.nameWithoutExtension, content)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.copy_xray_share_format)) },
                                            onClick = {
                                                shareMenuFile = null
                                                scope.launch(Dispatchers.IO) {
                                                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                                                    val vlessLink = content?.let {
                                                        generateVlessShareLink(file.nameWithoutExtension, it)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        if (vlessLink == null) {
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(R.string.export_failed),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } else {
                                                            copyTextToClipboard(file.nameWithoutExtension, vlessLink)
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.copy_config_file)) },
                                            onClick = {
                                                shareMenuFile = null
                                                scope.launch(Dispatchers.IO) {
                                                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                                                    withContext(Dispatchers.Main) {
                                                        if (content == null) {
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(R.string.export_failed),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } else {
                                                            copyTextToClipboard(file.nameWithoutExtension, content)
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.export_config_to_json)) },
                                            onClick = {
                                                shareMenuFile = null
                                                scope.launch(Dispatchers.IO) {
                                                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                                                    withContext(Dispatchers.Main) {
                                                        if (content == null) {
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(R.string.export_failed),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } else {
                                                            pendingExport = PendingExport(content)
                                                            createJsonLauncher.launch(file.name)
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    IconButton(onClick = { showDeleteDialog.value = file }) {
                                        Icon(
                                            painterResource(R.drawable.delete),
                                            contentDescription = "Delete"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog.value?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog.value = null },
            title = { Text(stringResource(R.string.delete_config)) },
            text = { Text(fileToDelete.name) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog.value = null
                    onDeleteConfigClick(fileToDelete) {
                        mainViewModel.refreshConfigFileList()
                        mainViewModel.updateSelectedConfigFile(null)
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
