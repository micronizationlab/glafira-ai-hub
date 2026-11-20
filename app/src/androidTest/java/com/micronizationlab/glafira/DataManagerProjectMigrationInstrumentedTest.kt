package com.micronizationlab.glafira

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataManagerProjectMigrationInstrumentedTest {

    @Test
    fun freshInstallCreatesAllBuiltInWorkspaces() = withIsolatedContext { context, _ ->
        val projects = loadProjects(context)

        assertEquals(
            listOf("default", "vodorodaktiv", "token", "chimera"),
            projects.map { it.id }
        )
        assertEquals(
            listOf("Личное", "ВодородАктив", "Токен", "Химера"),
            projects.map { it.name }
        )
        assertTrue(File(context.filesDir, "projects.json").isFile)
    }

    @Test
    fun existingUserProjectsAndOrderArePreservedAndMissingBuiltInsAppended() =
        withIsolatedContext { context, _ ->
            val existing = listOf(
                Project("custom-business", "Мой бизнес"),
                Project("default", "Личное пользователя"),
                Project("token", "Токен (моя папка)")
            )
            saveProjects(context, existing)

            val migrated = loadProjects(context)

            assertEquals(
                listOf("custom-business", "default", "token", "vodorodaktiv", "chimera"),
                migrated.map { it.id }
            )
            assertEquals("Мой бизнес", migrated[0].name)
            assertEquals("Личное пользователя", migrated[1].name)
            assertEquals("Токен (моя папка)", migrated[2].name)
        }

    @Test
    fun repeatedLaunchMigrationDoesNotCreateDuplicateBuiltIns() =
        withIsolatedContext { context, _ ->
            saveProjects(context, listOf(Project("custom", "Custom")))

            val first = loadProjects(context)
            val second = loadProjects(context)

            assertEquals(first, second)
            assertEquals(5, second.size)
            assertEquals(second.size, second.map { it.id }.distinct().size)
        }

    @Test
    fun malformedProjectFileRecoversToBuiltInWorkspaces() =
        withIsolatedContext { context, _ ->
            File(context.filesDir, "projects.json").writeText("{malformed")

            val recovered = loadProjects(context)

            assertEquals(
                listOf("default", "vodorodaktiv", "token", "chimera"),
                recovered.map { it.id }
            )
            assertTrue(File(context.filesDir, "projects.json").readText().startsWith("["))
        }

    private inline fun withIsolatedContext(block: (Context, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "project-migration-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        try {
            block(context, directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
