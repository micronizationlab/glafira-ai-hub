package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveEntryPolicyTest {
    @Test
    fun acceptsDistinctManifestAndDataEntries() {
        val names = mutableSetOf<String>()
        BackupArchive.requireUniqueArchiveEntryName(names, "manifest.json")
        BackupArchive.requireUniqueArchiveEntryName(names, "data/core/tasks.db")
        assertEquals(2, names.size)
    }

    @Test
    fun rejectsNonCanonicalPathSegmentsBeforeStaging() {
        for (name in listOf(
            "data/core/./tasks.db",
            "data/core/../tasks.db",
            "data//core/tasks.db",
            "/data/core/tasks.db",
            "data\\core\\tasks.db"
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                BackupArchive.validateArchiveEntryName(name)
            }
            assertEquals("Обнаружен небезопасный путь в архиве.", error.message)
        }
    }

    @Test
    fun rejectsCanonicalDestinationAliases() {
        val names = mutableSetOf<String>()
        BackupArchive.validateArchiveEntryName("data/core/tasks.db")
        BackupArchive.requireUniqueArchiveEntryName(names, "data/core/tasks.db")
        val alias = "data/core/./tasks.db"
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.validateArchiveEntryName(alias)
        }
    }

    @Test
    fun rejectsDuplicateManifestAndDataPaths() {
        for (name in listOf("manifest.json", "data/core/tasks.db")) {
            val names = mutableSetOf(name)
            val error = assertThrows(IllegalArgumentException::class.java) {
                BackupArchive.requireUniqueArchiveEntryName(names, name)
            }
            assertEquals("Повторная запись в архиве: $name", error.message)
        }
    }
}
