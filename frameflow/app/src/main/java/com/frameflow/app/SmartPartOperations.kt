package com.frameflow.app

object SmartPartOperations {
    /**
     * Runs limb identification without allowing an asynchronous model callback to
     * mutate a frame/layer that the user has already deleted or left.
     */
    fun identifyAndSplit(
        editor: EditorState,
        history: ProjectHistory,
        onSuccess: (Int) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        editor.ensureIndices()
        val project = editor.project
        val sourceFrame = editor.frame
        val sourceLayer = editor.layer
        if (!sourceLayer.hasRaster) {
            onFailure(IllegalStateException("Select an imported/image layer first"))
            return
        }
        SmartLimbIdentifier.splitLayer(
            sourceLayer,
            onSuccess = { parts ->
                runCatching {
                    if (parts.isEmpty()) error("No usable parts were detected")
                    val frameIndex = project.frames.indexOf(sourceFrame)
                    if (frameIndex < 0) error("The source frame no longer exists")
                    val layerIndex = sourceFrame.layers.indexOf(sourceLayer)
                    if (layerIndex < 0) error("The source layer changed while identification was running")
                    history.checkpoint()
                    val original = sourceFrame.layers.removeAt(layerIndex)
                    val newLayers = parts.map { part ->
                        LayerState(
                            name = part.name,
                            part = part.part,
                            rasterPngBase64 = part.pngBase64,
                            rasterName = part.name,
                            opacity = original.opacity,
                            offsetX = original.offsetX,
                            offsetY = original.offsetY,
                            scaleX = original.scaleX,
                            scaleY = original.scaleY,
                            rotationDeg = original.rotationDeg
                        )
                    }
                    sourceFrame.layers.addAll(layerIndex, newLayers)
                    editor.frameIndex = frameIndex
                    editor.layerIndex = layerIndex.coerceIn(sourceFrame.layers.indices)
                    editor.selectLayer(editor.layerIndex)
                    project.touch()
                    newLayers.size
                }.onSuccess(onSuccess).onFailure(onFailure)
            },
            onFailure = onFailure
        )
    }
}
