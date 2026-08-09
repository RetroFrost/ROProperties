package com.frameflow.app

import androidx.compose.runtime.mutableStateListOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val MAX_HISTORY_ENTRIES = 50
private const val MAX_HISTORY_BYTES = 32 * 1024 * 1024
private const val MAX_SINGLE_HISTORY_BYTES = 12 * 1024 * 1024
private const val MAX_INFLATED_HISTORY_BYTES = 512 * 1024 * 1024

private data class HistorySnapshot(val compressed: ByteArray, val sourceHash: Int)

class ProjectHistory(private val project: ProjectState) {
    private val undoStack = mutableStateListOf<HistorySnapshot>()
    private val redoStack = mutableStateListOf<HistorySnapshot>()
    private var undoBytes = 0
    private var redoBytes = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Captures the state before an edit. History has a strict memory budget so a
     * project with large imported PNGs cannot OOM simply because a control is dragged.
     */
    fun checkpoint() {
        val snapshot = createSnapshot() ?: return
        val last = undoStack.lastOrNull()
        if (last?.sourceHash == snapshot.sourceHash && last.compressed.contentEquals(snapshot.compressed)) return
        if (snapshot.compressed.size > MAX_SINGLE_HISTORY_BYTES) {
            // Keep the editor usable rather than risking an OOM. The project itself
            // remains autosaved/recoverable; huge individual states are not retained.
            clearRedo()
            return
        }
        undoStack.add(snapshot)
        undoBytes += snapshot.compressed.size
        trimUndo()
        clearRedo()
    }

    fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        undoBytes -= snapshot.compressed.size
        val current = createSnapshot()
        val restored = restoreSnapshot(snapshot) ?: return false
        if (current != null && current.compressed.size <= MAX_SINGLE_HISTORY_BYTES) {
            redoStack.add(current)
            redoBytes += current.compressed.size
            trimRedo()
        }
        project.replaceFrom(restored)
        return true
    }

    fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        redoBytes -= snapshot.compressed.size
        val current = createSnapshot()
        val restored = restoreSnapshot(snapshot) ?: return false
        if (current != null && current.compressed.size <= MAX_SINGLE_HISTORY_BYTES) {
            undoStack.add(current)
            undoBytes += current.compressed.size
            trimUndo()
        }
        project.replaceFrom(restored)
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        undoBytes = 0
        redoBytes = 0
    }

    private fun createSnapshot(): HistorySnapshot? = runCatching {
        val raw = projectToJson(project).toString().toByteArray(Charsets.UTF_8)
        val hash = raw.contentHashCode()
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(raw) }
            output.toByteArray()
        }
        HistorySnapshot(compressed, hash)
    }.getOrNull()

    private fun restoreSnapshot(snapshot: HistorySnapshot): ProjectState? = runCatching {
        val raw = ByteArrayOutputStream().use { output ->
            GZIPInputStream(ByteArrayInputStream(snapshot.compressed)).use { gzip ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_INFLATED_HISTORY_BYTES) { "History snapshot is too large" }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
        projectFromJson(raw.toString(Charsets.UTF_8))
    }.getOrNull()

    private fun trimUndo() {
        while (undoStack.size > MAX_HISTORY_ENTRIES || undoBytes > MAX_HISTORY_BYTES) {
            val removed = undoStack.removeAt(0)
            undoBytes -= removed.compressed.size
        }
    }

    private fun trimRedo() {
        while (redoStack.size > MAX_HISTORY_ENTRIES || redoBytes > MAX_HISTORY_BYTES) {
            val removed = redoStack.removeAt(0)
            redoBytes -= removed.compressed.size
        }
    }

    private fun clearRedo() {
        redoStack.clear()
        redoBytes = 0
    }
}
