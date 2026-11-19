package com.lanbridge.app.data.storage

import android.content.Context
import android.util.Log
import com.lanbridge.app.data.model.HistoryItem
import com.lanbridge.app.data.model.jsonConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File

class HistoryStorage(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val historyFile = File(context.filesDir, "history_items.json")
    private val _itemsFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
    val itemsFlow: StateFlow<List<HistoryItem>> = _itemsFlow.asStateFlow()

    companion object {
        private const val TAG = "HistoryStorage"
        private const val MAX_ITEMS = 50
    }

    init {
        loadHistory()
    }

    private fun loadHistory() {
        try {
            if (historyFile.exists()) {
                val jsonString = historyFile.readText()
                val list = jsonConfig.decodeFromString<List<HistoryItem>>(jsonString)
                _itemsFlow.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history from disk", e)
            _itemsFlow.value = emptyList()
        }
    }

    private fun saveHistory(list: List<HistoryItem>) {
        scope.launch {
            try {
                val json = jsonConfig.encodeToString(list)
                historyFile.writeText(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist history to disk", e)
            }
        }
    }

    @Synchronized
    fun addItem(item: HistoryItem) {
        val current = _itemsFlow.value.toMutableList()
        // Deduplicate if identical sha256 already exists in recent items
        current.removeAll { it.sha256 == item.sha256 }
        current.add(0, item)
        val trimmed = if (current.size > MAX_ITEMS) current.take(MAX_ITEMS) else current
        _itemsFlow.value = trimmed
        saveHistory(trimmed)
    }

    @Synchronized
    fun removeItem(id: String) {
        val current = _itemsFlow.value.toMutableList()
        if (current.removeAll { it.id == id }) {
            _itemsFlow.value = current
            saveHistory(current)
        }
    }

    @Synchronized
    fun clearHistory() {
        _itemsFlow.value = emptyList()
        saveHistory(emptyList())
    }

    fun getItems(): List<HistoryItem> = _itemsFlow.value
}
