package com.frameflow.app

import androidx.compose.runtime.Composable

/**
 * Stable entry point for the Frameflow editor.
 *
 * Release-hardening note: all visible editor functionality is routed through
 * FullEditorScreen. Experimental/future UI must not be exposed here until its
 * actions are backed by real project operations and covered by CI/runtime tests.
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
