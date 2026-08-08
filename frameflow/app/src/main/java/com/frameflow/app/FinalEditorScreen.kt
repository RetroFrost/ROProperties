package com.frameflow.app

import androidx.compose.runtime.Composable

/**
 * Stable entry point for the Frameflow editor.
 *
 * The production editor remains the battle-tested FullEditorScreen while the
 * final tools are split into independent modules. ReliableControls.kt shadows
 * the old Material slider with the continuous gesture implementation and fixes
 * the timeline's unstable frame identity, so the duration/size/opacity drags no
 * longer get cancelled mid-gesture.
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
    FullEditorScreen(
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
