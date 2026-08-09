package com.frameflow.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectRecoveryRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ids = mutableListOf<String>()

    @After
    fun cleanup() {
        val repository = ProjectRepository(context)
        ids.forEach(repository::delete)
    }

    @Test
    fun corruptPrimaryRecoversLastValidBackup() {
        val repository = ProjectRepository(context)
        val project = repository.createProject("Recovery source")
        ids += project.id
        project.frames[0].durationMs = 777
        repository.save(project)
        // Second valid save creates a recovery snapshot of the first valid file.
        project.name = "Latest valid"
        project.frames[0].durationMs = 888
        repository.save(project)

        val primary = File(context.filesDir, "frameflow-projects/${project.id}.frameflow")
        assertTrue(primary.isFile)
        primary.writeText("{ definitely not valid json")

        val recovered = repository.load(project.id)
        assertNotNull(recovered)
        // Recovery is the previous known-good save, never the corrupt bytes.
        assertEquals(777, recovered!!.frames[0].durationMs)
    }

    @Test
    fun duplicateHasIndependentIdentityAndDeleteCannotCrossDelete() {
        val repository = ProjectRepository(context)
        val original = repository.createProject("Original")
        ids += original.id
        original.frames[0].layers[0].strokes += StrokeData(
            points = listOf(CanvasPoint(1f, 1f), CanvasPoint(20f, 20f)),
            colorArgb = 0xFF101010.toInt(), width = 5f, alpha = 1f, erase = false
        )
        repository.save(original)
        val copy = repository.duplicate(original)
        ids += copy.id
        assertNotEquals(original.id, copy.id)
        assertTrue(repository.listProjects().any { it.id == original.id })
        assertTrue(repository.listProjects().any { it.id == copy.id })

        repository.delete(copy.id)
        assertNull(repository.load(copy.id))
        assertNotNull(repository.load(original.id))
        assertEquals(1, repository.load(original.id)!!.frames[0].layers[0].strokes.size)
    }
}
