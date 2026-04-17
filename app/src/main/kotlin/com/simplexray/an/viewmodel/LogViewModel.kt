package com.simplexray.an.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "LogViewModel"

@OptIn(FlowPreview::class)
class LogViewModel(application: Application) :
    AndroidViewModel(application) {

    private val logFileManager = LogFileManager(application)

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filteredEntries = MutableStateFlow<List<String>>(emptyList())
    val filteredEntries: StateFlow<List<String>> = _filteredEntries.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    private val _hasLogsToExport = MutableStateFlow(false)
    val hasLogsToExport: StateFlow<Boolean> = _hasLogsToExport.asStateFlow()

    // --- Selection state ---
    private val _selectionAnchor = MutableStateFlow<Int?>(null)
    val selectionAnchor: StateFlow<Int?> = _selectionAnchor.asStateFlow()

    private val _selectionEnd = MutableStateFlow<Int?>(null)
    val selectionEnd: StateFlow<Int?> = _selectionEnd.asStateFlow()

    // --- Save result event ---
    private val _saveResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val saveResult: SharedFlow<Boolean> = _saveResult.asSharedFlow()

    private val logMutex = Mutex()

    private var logUpdateReceiver: BroadcastReceiver

    init {
        Log.d(TAG, "LogViewModel initialized.")
        logUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (TProxyService.ACTION_LOG_UPDATE == intent.action) {
                    val newLogs = intent.getStringArrayListExtra(TProxyService.EXTRA_LOG_DATA)
                    if (!newLogs.isNullOrEmpty()) {
                        Log.d(TAG, "Received log update broadcast with ${newLogs.size} entries.")
                        viewModelScope.launch {
                            processNewLogs(newLogs)
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Received log update broadcast, but log data list is null or empty."
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            logEntries.collect { entries ->
                _hasLogsToExport.value = entries.isNotEmpty() && logFileManager.logFile.exists()
            }
        }
        viewModelScope.launch {
            combine(
                logEntries,
                searchQuery.debounce(200)
            ) { logs, query ->
                if (query.isBlank()) logs
                else logs.filter { it.contains(query, ignoreCase = true) }
            }
                .flowOn(Dispatchers.Default)
                .collect { _filteredEntries.value = it }
        }
        // Clear selection on subsequent search query changes (drop(1) skips the initial value)
        viewModelScope.launch {
            searchQuery.drop(1).collect { clearSelection() }
        }
    }

    fun registerLogReceiver(context: Context) {
        val filter = IntentFilter(TProxyService.ACTION_LOG_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(logUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(logUpdateReceiver, filter)
        }
        Log.d(TAG, "Log receiver registered.")
    }

    fun unregisterLogReceiver(context: Context) {
        context.unregisterReceiver(logUpdateReceiver)
        Log.d(TAG, "Log receiver unregistered.")
    }

    fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            clearSelection()
            Log.d(TAG, "Loading logs.")
            val savedLogData = logFileManager.readLogs()
            val initialLogs = if (!savedLogData.isNullOrEmpty()) {
                savedLogData.split("\n").filter { it.trim().isNotEmpty() }
            } else {
                emptyList()
            }
            processInitialLogs(initialLogs)
        }
    }

    /** Reload logs from the log file (manual refresh). */
    fun refreshLogs() {
        loadLogs()
    }

    private suspend fun processInitialLogs(initialLogs: List<String>) {
        logMutex.withLock {
            _logEntries.value = initialLogs.reversed()
        }
        Log.d(TAG, "Processed initial logs: ${_logEntries.value.size} entries.")
    }

    private suspend fun processNewLogs(newLogs: ArrayList<String>) {
        // Don't update the log list while the user has a selection active;
        // index shifts would silently change which lines are highlighted.
        if (_selectionAnchor.value != null) {
            Log.d(TAG, "Skipping log update broadcast – selection mode active.")
            return
        }
        val nonEmptyLogs = newLogs.filter { it.trim().isNotEmpty() }
        if (nonEmptyLogs.isNotEmpty()) {
            logMutex.withLock {
                _logEntries.value = nonEmptyLogs + _logEntries.value
            }
            Log.d(TAG, "Added ${nonEmptyLogs.size} new log entries.")
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logMutex.withLock {
                _logEntries.value = emptyList()
            }
            clearSelection()
            Log.d(TAG, "Logs cleared.")
        }
    }

    // --- Selection logic ---

    /**
     * Called when the user taps a log entry at [index] in the current filtered list.
     *
     * - First tap: immediately selects that single entry (anchor = end = index).
     * - Subsequent taps: extend the selection to the new index; anchor stays fixed.
     * - Cancel via [clearSelection].
     */
    fun onLogEntryClick(index: Int) {
        if (_selectionAnchor.value == null) {
            _selectionAnchor.value = index
            _selectionEnd.value = index
        } else {
            _selectionEnd.value = index
        }
    }

    fun clearSelection() {
        _selectionAnchor.value = null
        _selectionEnd.value = null
    }

    // --- Export ---

    /**
     * Writes all log entries (in chronological order, oldest first) to [uri].
     * Emits `true` on success or `false` on failure via [saveResult].
     */
    fun writeLogsToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { os ->
                    val text = _logEntries.value.reversed().joinToString("\n")
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                _saveResult.tryEmit(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write logs to URI", e)
                _saveResult.tryEmit(false)
            }
        }
    }

    fun getLogFile(): File {
        return logFileManager.logFile
    }
}

class LogViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LogViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
