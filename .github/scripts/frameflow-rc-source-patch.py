from pathlib import Path


def replace_once(path: str, old: str, new: str, already: str | None = None) -> None:
    file = Path(path)
    text = file.read_text()
    if old in text:
        if text.count(old) != 1:
            raise SystemExit(f"{path}: expected exactly one patch target")
        file.write_text(text.replace(old, new))
        return
    if already is not None and already in text:
        return
    raise SystemExit(f"{path}: patch target not found")


replace_once(
    "frameflow/app/src/main/java/com/frameflow/app/MediaExporter.kt",
    "return Array(bitmap.width) { x -> IntArray(bitmap.height) { y -> raw[y * bitmap.width + x] } }",
    """return Array(bitmap.height) { y ->
        IntArray(bitmap.width) { x -> raw[y * bitmap.width + x] }
    }""",
    "return Array(bitmap.height) { y ->"
)

replace_once(
    "frameflow/app/src/main/java/com/frameflow/app/ProjectRepository.kt",
    """        audioFile(project)?.let { audio ->
            val dest = File(mediaDir(clone.id).apply { mkdirs() }, safeFileName(audio.name))
            audio.inputStream().use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
            clone.audioFileName = dest.name
        }""",
    """        val sourceMediaDir = mediaDir(project.id)
        val cloneMediaDir = mediaDir(clone.id)
        if (sourceMediaDir.isDirectory) {
            check(sourceMediaDir.copyRecursively(cloneMediaDir, overwrite = true)) { "Unable to duplicate project media" }
        }
        clone.audioFileName = project.audioFileName
            ?.let(::safeFileName)
            ?.takeIf { File(cloneMediaDir, it).isFile }""",
    "val sourceMediaDir = mediaDir(project.id)"
)
