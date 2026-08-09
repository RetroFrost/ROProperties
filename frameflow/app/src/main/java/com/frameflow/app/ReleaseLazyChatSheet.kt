package com.frameflow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseLazyChatSheet(
    editor: EditorState,
    history: ProjectHistory,
    dismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val store = remember { LazyChatStore(context) }
    val messages = remember(editor.project.id) {
        mutableStateListOf<LazyChatMessage>().apply { addAll(store.load(editor.project.id)) }
    }
    val listState = rememberLazyListState()
    var input by remember(editor.project.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun persist() = store.save(editor.project.id, messages)

    fun send(raw: String) {
        val text = raw.trim().take(1200)
        if (text.isBlank() || busy) return
        input = ""
        busy = true
        messages += LazyChatMessage(role = "user", text = text)
        persist()

        val result = runCatching { LazyChatEngine.execute(editor, history, text) }
            .getOrElse { error ->
                LazyChatEngine.Result(
                    reply = "I couldn't apply that safely: ${error.message ?: "unknown error"}.",
                    changedProject = false,
                    understoodCommands = 0
                )
            }
        messages += LazyChatMessage(role = "assistant", text = result.reply)
        while (messages.size > 120) messages.removeAt(0)
        persist()
        busy = false
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.lastIndex) }
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 14.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lazy Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tell Frameflow what to change. Replies map to real editable frames, layers and timing.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (messages.isNotEmpty()) {
                    TextButton(onClick = {
                        messages.clear()
                        store.clear(editor.project.id)
                    }) { Text("Clear chat") }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val suggestions = listOf(
                    "Clone and hold 2 seconds",
                    "Make him wave",
                    "Bounce and zoom in",
                    "Select right arm then rotate 20",
                    "Make it smoother"
                )
                suggestions.forEach { suggestion ->
                    item {
                        SuggestionChip(
                            onClick = { send(suggestion) },
                            label = { Text(suggestion) },
                            enabled = !busy
                        )
                    }
                }
            }

            Surface(
                Modifier.weight(1f).fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                if (messages.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(18.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No setup required", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Examples: “clone this and hold it for 1.5 seconds”, “make him angry then wave”, “select the left leg then rotate -20”, or “shake and zoom in for 2 seconds”.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(messages.size, key = { messages[it].id }) { index ->
                            val message = messages[index]
                            val user = message.role == "user"
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    modifier = Modifier.widthIn(max = 330.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                    color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                        Text(if (user) "You" else "Frameflow", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(1200) },
                    modifier = Modifier.weight(1f),
                    label = { Text("What should I change?") },
                    placeholder = { Text("e.g. clone, make him happy, then wave") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !busy
                )
                Button(
                    onClick = { send(input) },
                    enabled = input.isNotBlank() && !busy,
                    modifier = Modifier.height(56.dp)
                ) { Text("Send") }
            }

            Text(
                "Lazy Chat works offline and uses the same undo/history system as manual editing. “Undo that” and “redo that” work directly in chat.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
