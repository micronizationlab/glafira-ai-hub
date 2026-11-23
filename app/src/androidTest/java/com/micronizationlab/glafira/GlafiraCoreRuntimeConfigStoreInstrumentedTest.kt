package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreRuntimeConfigStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraCoreRuntimeConfigStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        store = GlafiraCoreRuntimeConfigStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun missingConfigurationInitializesPausedWithConservativeDefaults() {
        val snapshot = requireNotNull(store.loadOrInitialize(null, null))
        assertFalse(snapshot.enabled)
        assertEquals(GlafiraRuntimeBudget(), snapshot.budget)
        assertEquals(1, snapshot.configVersion)
    }

    @Test
    fun legacySettingsAreMigratedOnceAndThenSqliteIsAuthoritative() {
        val legacyBudget = GlafiraRuntimeBudget(
            maxWallClockMillis = 120_000L,
            maxSteps = 1,
            maxRetries = 3,
            maxConcurrentTasks = 2,
            maxCostMicros = 50_000L
        )
        assertTrue(requireNotNull(store.loadOrInitialize(true, legacyBudget)).enabled)
        store.close()
        store = GlafiraCoreRuntimeConfigStore(context)

        val persisted = requireNotNull(store.read())
        assertTrue(persisted.enabled)
        assertEquals(legacyBudget, persisted.budget)

        store.setEnabled(false)
        store.setBudget(GlafiraRuntimeBudget(maxRetries = 1, maxConcurrentTasks = 1))
        val changed = requireNotNull(store.read())
        assertFalse(changed.enabled)
        assertEquals(1, changed.budget.maxRetries)

        val auditCount = store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM runtime_config_audit", null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
        assertEquals(3, auditCount)
    }

    @Test
    fun unsupportedConfigVersionFailsClosedAndIsNotOverwritten() {
        store.loadOrInitialize(true, null)
        store.writableDatabase.execSQL(
            "UPDATE runtime_config SET config_version = 999 WHERE singleton_id = 1"
        )
        assertNull(store.read())
        assertNull(store.loadOrInitialize(true, null))
        val version = store.readableDatabase.rawQuery(
            "SELECT config_version FROM runtime_config WHERE singleton_id = 1", null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
        assertEquals(999, version)
    }

    @Test
    fun invalidPersistedBudgetFailsClosed() {
        store.loadOrInitialize(true, null)
        store.writableDatabase.execSQL(
            "UPDATE runtime_config SET max_steps = 2 WHERE singleton_id = 1"
        )
        assertNull(store.read())
        assertNull(store.loadOrInitialize(true, null))
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_runtime_config.db"
    }
}
