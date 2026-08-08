package com.frameflow.app

import androidx.compose.runtime.mutableStateListOf

class ProjectHistory(private val project: ProjectState) {
    private val undoStack = mutableStateListOf<String>()
    private val redoStack = mutableStateListOf<String>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun checkpoint() {
        val snapshot = projectToJson(project).toString()
        if (undoStack.lastOrNull() != snapshot) {
            undoStack.add(snapshot)
            if (undoStack.size > 50) undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.add(projectToJson(project).toString())
        project.replaceFrom(projectFromJson(snapshot))
        return true
    }

    fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.add(projectToJson(project).toString())
        project.replaceFrom(projectFromJson(snapshot))
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
