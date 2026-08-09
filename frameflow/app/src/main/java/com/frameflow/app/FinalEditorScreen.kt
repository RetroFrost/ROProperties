package com.frameflow.app

import androidx.compose.runtime.Composable

/**
 * Shipping editor entry point. Only release-backed tools are exposed here.
 */
@Composable
fun FinalEditorScreen(
    project: ProjectState,
    repository: ProjectRepository,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onExportProject: () -> Unit,
    onExportCurrentPng: () -> Unit,
    onExportFramesZip: () -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit
) {
    ReleaseEditorScreen(
        project = project,
        repository = repository,
        onBack = onBack,
        onSave = onSave,
        onImportImage = onImportImage,
        onImportAudio = onImportAudio,
        onExportProject = onExportProject,
        onExportCurrentPng = onExportCurrentPng,
        onExportFramesZip = onExportFramesZip,
        onExportGif = onExportGif,
        onExportMp4 = onExportMp4
    )
}
