package com.frameflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { FrameflowApp() }
        }
    }
}

@Composable
fun FrameflowApp() {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context.applicationContext) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(repository.listProjects()) }
    var currentProject by remember { mutableStateOf<ProjectState?>(null) }

    fun refreshProjects() {
        projects = repository.listProjects()
    }

    fun safeName(name: String): String = name
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .ifBlank { "Frameflow" }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { repository.importProject(uri) }
                .onSuccess {
                    currentProject = it
                    refreshProjects()
                    scope.launch { snackbar.showSnackbar("Project imported") }
                }
                .onFailure { error ->
                    scope.launch { snackbar.showSnackbar("Import failed: ${error.message ?: "unknown error"}") }
                }
        }
    }

    val exportProjectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val project = currentProject
        if (uri != null && project != null) {
            runCatching { repository.exportProject(project, uri) }
                .onSuccess { scope.launch { snackbar.showSnackbar("Editable project exported") } }
                .onFailure { scope.launch { snackbar.showSnackbar("Export failed") } }
        }
    }

    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val project = currentProject
        if (uri != null && project != null) {
            runCatching { repository.exportCurrentPng(project, 0, uri) }
                .onSuccess { scope.launch { snackbar.showSnackbar("PNG exported") } }
                .onFailure { scope.launch { snackbar.showSnackbar("PNG export failed") } }
        }
    }

    val exportFramesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val project = currentProject
        if (uri != null && project != null) {
            runCatching { repository.exportFramesZip(project, uri) }
                .onSuccess { scope.launch { snackbar.showSnackbar("Frame archive exported") } }
                .onFailure { scope.launch { snackbar.showSnackbar("Frame archive export failed") } }
        }
    }

    val revision = currentProject?.revision
    LaunchedEffect(currentProject?.id, revision) {
        val project = currentProject ?: return@LaunchedEffect
        if (project.revision > 0) {
            delay(650)
            repository.save(project)
            refreshProjects()
        }
    }

    BackHandler(enabled = currentProject != null) {
        currentProject?.let(repository::save)
        currentProject = null
        refreshProjects()
    }

    Box(Modifier.fillMaxSize()) {
        val project = currentProject
        if (project == null) {
            HomeScreen(
                projects = projects,
                onNewProject = { name, width, height ->
                    val created = ProjectState(name = name, canvasWidth = width, canvasHeight = height)
                    repository.save(created)
                    currentProject = created
                    refreshProjects()
                },
                onOpenProject = { id ->
                    currentProject = repository.load(id)
                    if (currentProject == null) {
                        scope.launch { snackbar.showSnackbar("That project could not be opened") }
                    }
                },
                onDuplicateProject = { id ->
                    repository.load(id)?.let(repository::duplicate)
                    refreshProjects()
                },
                onDeleteProject = { id ->
                    repository.delete(id)
                    refreshProjects()
                },
                onImportProject = {
                    importLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/plain", "*/*"))
                }
            )
        } else {
            EditorScreen(
                project = project,
                onBack = {
                    repository.save(project)
                    currentProject = null
                    refreshProjects()
                },
                onSave = {
                    repository.save(project)
                    refreshProjects()
                    scope.launch { snackbar.showSnackbar("Saved") }
                },
                onExportProject = {
                    exportProjectLauncher.launch("${safeName(project.name)}.frameflow")
                },
                onExportCurrentPng = {
                    exportPngLauncher.launch("${safeName(project.name)}-frame.png")
                },
                onExportFramesZip = {
                    exportFramesLauncher.launch("${safeName(project.name)}-frames.zip")
                }
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
