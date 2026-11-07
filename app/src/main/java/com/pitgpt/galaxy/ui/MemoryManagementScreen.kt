package com.pitgpt.galaxy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.core.model.MemoryItem
import com.pitgpt.galaxy.core.model.MemoryScope
import com.pitgpt.galaxy.core.repository.MemoryRepository
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private val MemoryNight = Color(0xFF080B18)
private val MemoryMuted = Color(0xFF9AA8C5)
private val MemoryIce = Color(0xFFB8D9FF)

@Composable
fun MemoryManagementScreen(
    repository: MemoryRepository,
    activeCharacterId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedScope by remember { mutableStateOf(MemoryScope.USER) }
    var memories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<MemoryItem?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    fun ownerId(scope: MemoryScope): String? =
        if (scope == MemoryScope.CHARACTER) activeCharacterId else null

    suspend fun reload() {
        loading = true
        error = null
        try {
            memories = repository.getAll(selectedScope, ownerId(selectedScope))
        } catch (failure: Exception) {
            error = "Не удалось загрузить память. Повтори попытку."
        } finally {
            loading = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val exportScopes = MemoryScope.entries.associateWith { memoryScope ->
                        repository.getAll(memoryScope, ownerId(memoryScope))
                    }
                    val root = JSONObject().put("format", "project-galaxy-memory-v1")
                        .put("exportedAtEpochMillis", System.currentTimeMillis())
                        .put("activeCharacterId", activeCharacterId)
                    exportScopes.forEach { (memoryScope, records) ->
                        root.put(memoryScope.name.lowercase(), JSONArray().apply {
                            records.forEach { item ->
                                put(JSONObject()
                                    .put("id", item.id)
                                    .put("scope", item.scope.name)
                                    .put("ownerCharacterId", item.ownerCharacterId)
                                    .put("content", item.content)
                                    .put("importance", item.importance)
                                    .put("createdAtEpochMillis", item.createdAtEpochMillis)
                                    .put("updatedAtEpochMillis", item.updatedAtEpochMillis))
                            }
                        })
                    }
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(root.toString(2))
                    } ?: kotlin.error("Unable to open export destination")
                }.onSuccess {
                    exportStatus = "Экспорт памяти сохранён."
                }.onFailure {
                    exportStatus = "Не удалось сохранить экспорт. Попробуй другое место."
                }
            }
        }
    }

    LaunchedEffect(selectedScope, activeCharacterId) { reload() }

    Column(
        Modifier.fillMaxSize().background(MemoryNight).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("‹ НАЗАД", color = MemoryIce) }
            Spacer(Modifier.weight(1f))
            Text("ПАМЯТЬ", color = Color.White, fontSize = 17.sp)
        }
        Text(
            "Просматривай сохранённые записи. Удаление необратимо и всегда требует подтверждения.",
            color = MemoryMuted,
            fontSize = 12.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MemoryScope.entries.forEach { memoryScope ->
                TextButton(
                    onClick = { selectedScope = memoryScope },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (memoryScope) {
                            MemoryScope.USER -> "МОЯ"
                            MemoryScope.CHARACTER -> "ПЕРСОНАЖ"
                            MemoryScope.SHARED -> "ОБЩАЯ"
                        },
                        color = if (selectedScope == memoryScope) Color.White else MemoryMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
        exportStatus?.let { Text(it, color = MemoryIce, fontSize = 12.sp) }
        if (loading) {
            Text("Загружаю память…", color = MemoryMuted)
        } else if (memories.isEmpty()) {
            Text("В этом разделе пока нет сохранённых записей.", color = MemoryMuted)
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(memories, key = { it.id }) { item ->
                    Column(
                        Modifier.fillMaxWidth().background(
                            Color(0xFF18213B), RoundedCornerShape(14.dp)
                        ).padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(item.content, color = Color.White, fontSize = 14.sp)
                        Text(
                            "Важность: ${item.importance} · Обновлено: ${DateFormat.getDateTimeInstance().format(Date(item.updatedAtEpochMillis))}",
                            color = MemoryMuted,
                            fontSize = 10.sp
                        )
                        TextButton(onClick = { pendingDelete = item }) {
                            Text("УДАЛИТЬ ЗАПИСЬ", color = Color(0xFFFF9B9B), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Button(
            onClick = { exportLauncher.launch("galaxy-memory-export.json") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28375D)),
            shape = RoundedCornerShape(14.dp)
        ) { Text("ЭКСПОРТИРОВАТЬ ПАМЯТЬ") }
        Button(
            onClick = { confirmDeleteAll = true },
            enabled = memories.isNotEmpty() && !loading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2938)),
            shape = RoundedCornerShape(14.dp)
        ) { Text("УДАЛИТЬ ВСЁ В ЭТОМ РАЗДЕЛЕ") }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("Эта запись памяти будет удалена без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { repository.delete(item.id) }
                            .onSuccess { reload() }
                            .onFailure { error = "Не удалось удалить запись." }
                    }
                }) { Text("УДАЛИТЬ", color = Color(0xFFFF7777)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("ОТМЕНА") }
            }
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Удалить всю память раздела?") },
            text = { Text("Будут удалены все ${memories.size} записей выбранного раздела. Другие разделы не затрагиваются.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        runCatching { repository.deleteAll(selectedScope, ownerId(selectedScope)) }
                            .onSuccess { reload() }
                            .onFailure { error = "Не удалось удалить память раздела." }
                    }
                }) { Text("УДАЛИТЬ ВСЁ", color = Color(0xFFFF7777)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("ОТМЕНА") }
            }
        )
    }
}
