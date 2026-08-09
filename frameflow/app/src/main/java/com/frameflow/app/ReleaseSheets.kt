package com.frameflow.app

import androidx.compose.runtime.Composable

@Composable
fun ReleaseSheets(
    sheet: ReleaseSheet?,
    editor: EditorState,
    history: ProjectHistory,
    repository: ProjectRepository,
    recentColours: MutableList<Int>,
    onionBefore: Int,
    onionAfter: Int,
    onionAlpha: Float,
    onionSelectedLayerOnly: Boolean,
    viewZoom: Float,
    onViewZoom: (Float) -> Unit,
    onResetView: () -> Unit,
    onOnionBefore: (Int) -> Unit,
    onOnionAfter: (Int) -> Unit,
    onOnionAlpha: (Float) -> Unit,
    onOnionLayerOnly: (Boolean) -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onSave: () -> Unit,
    onTool: (ReleaseTool) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (sheet) {
        ReleaseSheet.Brushes -> ReleaseBrushSheet(editor, false, onTool, onDismiss)
        ReleaseSheet.Erasers -> ReleaseBrushSheet(editor, true, onTool, onDismiss)
        ReleaseSheet.Colour -> ReleaseColourSheet(editor, recentColours, onDismiss)
        ReleaseSheet.Layers -> ReleaseLayersSheet(editor, history, onImportImage, onDismiss)
        ReleaseSheet.Onion -> ReleaseOnionSheet(
            onionBefore, onionAfter, onionAlpha, onionSelectedLayerOnly,
            onOnionBefore, onOnionAfter, onOnionAlpha, onOnionLayerOnly, onDismiss
        )
        ReleaseSheet.Smart -> ReleaseSmartSheet(editor, history, onMessage, onDismiss)
        ReleaseSheet.LazyChat -> ReleaseLazyChatSheet(editor, history, onDismiss)
        ReleaseSheet.Audio -> ReleaseAudioSheet(editor, history, repository, onImportAudio, onMessage, onDismiss)
        ReleaseSheet.Project -> ReleaseProjectSheet(editor.project, history, onSave, onDismiss)
        ReleaseSheet.Camera -> ReleaseCameraSheet(editor, history, viewZoom, onViewZoom, onResetView, onDismiss)
        ReleaseSheet.Tools -> ReleaseToolsSheet(onTool, onDismiss)
        null -> Unit
    }
}
