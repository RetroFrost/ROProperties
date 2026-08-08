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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FrameflowApp() } }
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

    fun refreshProjects() { projects = repository.listProjects() }
    fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Frameflow" }
    fun notify(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    val importProjectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.importProject(uri) } }
                .onSuccess { currentProject = it; refreshProjects(); notify("Project imported") }
                .onFailure { notify("Import failed: ${it.message ?: "unknown error"}") }
        }
    }

    val importImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.importImageLayer(project, project.activeFrameIndex, uri) }
            }.onSuccess {
                project.touch()
                notify("Image added to frame ${project.activeFrameIndex + 1}")
            }.onFailure { notify("Image import failed: ${it.message ?: "unknown error"}") }
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.importAudio(project, uri) } }
                .onSuccess { notify("Audio track imported") }
                .onFailure { notify("Audio import failed: ${it.message ?: "unknown error"}") }
        }
    }

    val exportProjectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.exportProject(project, uri) } }
                .onSuccess { notify("Editable project exported") }
                .onFailure { notify("Project export failed: ${it.message ?: "unknown error"}") }
        }
    }

    val exportPngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.exportCurrentPng(project, project.activeFrameIndex, uri) } }
                .onSuccess { notify("Frame ${project.activeFrameIndex + 1} PNG exported") }
                .onFailure { notify("PNG export failed: ${it.message ?: "unknown error"}") }
        }
    }

    val exportFramesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.exportFramesZip(project, uri) } }
                .onSuccess { notify("Frame archive exported") }
                .onFailure { notify("Frame archive failed: ${it.message ?: "unknown error"}") }
        }
    }

    val exportGifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            notify("Rendering GIF…")
            runCatching { withContext(Dispatchers.IO) { repository.exportGif(project, uri) } }
                .onSuccess { notify("Animated GIF exported") }
                .onFailure { notify("GIF export failed: ${it.message ?: "unknown error"}") }
        }
    }

    val exportMp4Launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val project = currentProject
        if (uri != null && project != null) scope.launch {
            notify("Rendering MP4…")
            runCatching { withContext(Dispatchers.IO) { repository.exportMp4(project, uri) } }
                .onSuccess { notify("MP4 exported") }
                .onFailure { notify("MP4 export failed: ${it.message ?: "unknown error"}") }
        }
    }

    val revision = currentProject?.revision
    LaunchedEffect(currentProject?.id, revision) {
        val project = currentProject ?: return@LaunchedEffect
        if (project.revision > 0) {
            delay(700)
            withContext(Dispatchers.IO) { repository.save(project) }
            refreshProjects()
        }
    }

    BackHandler(enabled = currentProject != null) {
        val project = currentProject
        if (project != null) scope.launch { withContext(Dispatchers.IO) { repository.save(project) } }
        currentProject = null
        refreshProjects()
    }

    Box(Modifier.fillMaxSize()) {
        val project = currentProject
        if (project == null) {
            HomeScreen(
                projects = projects,
                onNewProject = { name, width, height, mode ->
                    val created = ProjectState(name = name, canvasWidth = width, canvasHeight = height, mode = mode)
                    repository.save(created)
                    currentProject = created
                    refreshProjects()
                },
                onOpenProject = { id ->
                    currentProject = repository.load(id)
                    if (currentProject == null) notify("That project could not be opened")
                },
                onDuplicateProject = { id -> repository.load(id)?.let(repository::duplicate); refreshProjects() },
                onDeleteProject = { id -> repository.delete(id); refreshProjects() },
                onImportProject = { importProjectLauncher.launch(arrayOf("application/octet-stream", "application/json", "text/plain", "*/*")) }
            )
        } else {
            FullEditorScreen(
                project = project,
                repository = repository,
                onBack = {
                    repository.save(project)
                    currentProject = null
                    refreshProjects()
                },
                onSave = { repository.save(project); refreshProjects(); notify("Saved") },
                onImportImage = { importImageLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) },
                onImportAudio = { importAudioLauncher.launch(arrayOf("audio/*")) },
                onExportProject = { exportProjectLauncher.launch("${safeName(project.name)}.frameflow") },
                onExportCurrentPng = { exportPngLauncher.launch("${safeName(project.name)}-frame-${project.activeFrameIndex + 1}.png") },
                onExportFramesZip = { exportFramesLauncher.launch("${safeName(project.name)}-frames.zip") },
                onExportGif = { exportGifLauncher.launch("${safeName(project.name)}.gif") },
                onExportMp4 = { exportMp4Launcher.launch("${safeName(project.name)}.mp4") }
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
