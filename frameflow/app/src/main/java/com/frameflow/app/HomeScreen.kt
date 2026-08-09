package com.frameflow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    projects: List<ProjectMeta>,
    onNewProject: (String, Int, Int, ProjectMode) -> Unit,
    onOpenProject: (String) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onImportProject: () -> Unit
) {
    var showNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectMeta?>(null) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Frameflow", fontWeight = FontWeight.Bold)
                        Text("Draw once. Clone. Move. Hold. Animate.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNew = true }, text = { Text("+ New animation") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(onClick = { showNew = true }, modifier = Modifier.weight(1f)) { Text("New project") }
                    OutlinedButton(onClick = onImportProject, modifier = Modifier.weight(1f)) { Text("Import .frameflow") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeInfoCard("Static", "Frame-by-frame drawing", Modifier.weight(1f))
                    ModeInfoCard("Auto", "Smart motion helpers", Modifier.weight(1f))
                    ModeInfoCard("Object", "Parts + reusable limbs", Modifier.weight(1f))
                }
            }

            if (projects.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(top = 20.dp), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No animations yet", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Start with a blank canvas or import a character. The + button clones the current frame so you only redraw what changes.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(onClick = { showNew = true }) { Text("Create first animation") }
                        }
                    }
                }
            } else {
                item { Text("Projects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project, { onOpenProject(project.id) }, { onDuplicateProject(project.id) }, { deleteTarget = project })
                }
            }
        }
    }

    if (showNew) {
        NewProjectDialog(
            onDismiss = { showNew = false },
            onCreate = { name, width, height, mode ->
                showNew = false
                onNewProject(name, width, height, mode)
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the local Frameflow project and its imported media from this device.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDeleteProject(target.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ModeInfoCard(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectMeta,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${project.frameCount} frame${if (project.frameCount == 1) "" else "s"} · ${project.width}×${project.height}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.modifiedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text("Open") }
                FilledTonalButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int, ProjectMode) -> Unit) {
    var name by remember { mutableStateOf("Untitled animation") }
    var width by remember { mutableIntStateOf(1080) }
    var height by remember { mutableIntStateOf(1080) }
    var mode by remember { mutableStateOf(ProjectMode.Static) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New animation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Workflow", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { mode = item },
                            label = { Text(item.label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text("Canvas", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(width == 1080 && height == 1080, { width = 1080; height = 1080 }, { Text("Square") })
                    FilterChip(width == 1080 && height == 1920, { width = 1080; height = 1920 }, { Text("Portrait") })
                    FilterChip(width == 1920 && height == 1080, { width = 1920; height = 1080 }, { Text("Landscape") })
                }
                Text("$width × $height", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name.trim().ifBlank { "Untitled animation" }, width, height, mode) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
