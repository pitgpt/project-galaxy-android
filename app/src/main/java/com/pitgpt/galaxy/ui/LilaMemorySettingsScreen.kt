package com.pitgpt.galaxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitgpt.galaxy.data.remote.GalaxyApiException
import com.pitgpt.galaxy.data.remote.HttpLilaMemoryClient
import com.pitgpt.galaxy.data.remote.LilaMemorySnippet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private val MemoryScreenText = Color(0xFFB8D9FF)
private val MemoryScreenMuted = Color(0xFF9AA8C5)

internal fun lilaMemoryDeleteNotice(deleted: Boolean): String =
    if (deleted) "Воспоминание удалено." else "Запись уже отсутствует или недоступна."

@Composable
fun LilaMemorySettingsScreen(
    client: HttpLilaMemoryClient,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<LilaMemorySnippet>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var editingMemoryId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LilaMemorySnippet?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        isLoading = true
        error = null
        try {
            memories = client.search(query, limit = 20)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = (exception as? GalaxyApiException)?.message
                ?: "Не удалось загрузить долговременную память."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !isSaving) { Text("НАЗАД", color = MemoryScreenText) }
            Spacer(Modifier.weight(1f))
            Text("LILA CORE", color = MemoryScreenMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "ДОЛГОВРЕМЕННАЯ ПАМЯТЬ",
            color = MemoryScreenText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Эти воспоминания принадлежат твоему аккаунту и хранятся в зашифрованном хранилище Core. Добавляй только то, что действительно хочешь сохранить. Удаление убирает запись из памяти Core.",
            color = MemoryScreenMuted,
            fontSize = 12.sp
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск по воспоминаниям") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            enabled = !isLoading && !isSaving,
            onClick = { scope.launch { reload() } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (query.isBlank()) "ПОКАЗАТЬ ПОСЛЕДНИЕ" else "НАЙТИ ВОСПОМИНАНИЯ")
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (editingMemoryId == null) "СОХРАНИТЬ ВОСПОМИНАНИЕ" else "ИЗМЕНИТЬ ВОСПОМИНАНИЕ",
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(4_001) },
                    label = { Text("Что Лиле важно помнить") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${draft.length}/4000 символов",
                    color = MemoryScreenMuted,
                    fontSize = 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isSaving && !isLoading && draft.isNotBlank() && draft.length <= 4_000,
                        onClick = {
                            scope.launch {
                                isSaving = true
                                error = null
                                notice = null
                                try {
                                    val memoryId = editingMemoryId ?: UUID.randomUUID().toString()
                                    client.save(memoryId, draft.trim())
                                    draft = ""
                                    editingMemoryId = null
                                    notice = "Воспоминание сохранено в Lila Core."
                                    reload()
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    error = (exception as? GalaxyApiException)?.message
                                        ?: "Не удалось сохранить воспоминание."
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isSaving) "СОХРАНЕНИЕ…" else "СОХРАНИТЬ")
                    }
                    if (editingMemoryId != null) {
                        TextButton(enabled = !isSaving, onClick = {
                            editingMemoryId = null
                            draft = ""
                        }) {
                            Text("ОТМЕНА", color = MemoryScreenText)
                        }
                    }
                }
            }
        }

        error?.let { Text(it, color = Color(0xFFFF9B9B), fontSize = 12.sp) }
        notice?.let { Text(it, color = Color(0xFF75E2BD), fontSize = 12.sp) }

        Text("ВОСПОМИНАНИЯ · ${memories.size}/20", color = MemoryScreenMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (isLoading) {
            Text("Загрузка памяти…", color = MemoryScreenMuted)
        } else if (memories.isEmpty()) {
            Text(
                if (query.isBlank()) "Пока нет сохранённых воспоминаний." else "По этому запросу ничего не найдено.",
                color = MemoryScreenMuted,
                fontSize = 12.sp
            )
        } else {
            memories.forEach { memory ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(memory.content, color = MemoryScreenText, fontSize = 14.sp)
                        Text(memory.memoryId, color = MemoryScreenMuted, fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !isSaving && !isLoading, onClick = {
                                editingMemoryId = memory.memoryId
                                draft = memory.content
                                notice = null
                                error = null
                            }) {
                                Text("ИЗМЕНИТЬ", color = MemoryScreenText)
                            }
                            TextButton(enabled = !isSaving && !isLoading, onClick = { pendingDelete = memory }) {
                                Text("УДАЛИТЬ", color = Color(0xFFFF9B9B))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!isSaving) pendingDelete = null },
            title = { Text("Удалить воспоминание?") },
            text = { Text("Эта запись будет удалена из долговременной памяти Lila Core.") },
            confirmButton = {
                TextButton(enabled = !isSaving, onClick = {
                    scope.launch {
                        isSaving = true
                        error = null
                        notice = null
                        try {
                            val deleted = client.delete(target.memoryId)
                            if (editingMemoryId == target.memoryId) {
                                editingMemoryId = null
                                draft = ""
                            }
                            pendingDelete = null
                            notice = lilaMemoryDeleteNotice(deleted)
                            reload()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            error = (exception as? GalaxyApiException)?.message
                                ?: "Не удалось удалить воспоминание."
                        } finally {
                            isSaving = false
                        }
                    }
                }) {
                    Text("УДАЛИТЬ")
                }
            },
            dismissButton = {
                TextButton(enabled = !isSaving, onClick = { pendingDelete = null }) { Text("ОТМЕНА") }
            }
        )
    }
}
