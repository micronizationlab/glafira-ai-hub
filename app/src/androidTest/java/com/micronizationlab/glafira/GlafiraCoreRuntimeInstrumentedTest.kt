package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GlafiraCoreRuntimeInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var runtime: GlafiraCoreRuntimeOwner

    @Before
    fun setUp() {
        runtime = GlafiraCoreRuntimeOwner.get(context)
        runtime.setEnabled(false)
    }

    @After
    fun tearDown() {
        runtime.setEnabled(false)
    }

    @Test
    fun ownerTogglePersistsAndSchedulesUniqueDeferredCoreWork() {
        assertFalse(runtime.isEnabled)

        runtime.setEnabled(true)
        assertTrue(runtime.isEnabled)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("glafira-core-queue-drain")
            .get(10, TimeUnit.SECONDS)

        assertTrue("Enabling Core runtime must enqueue unique WorkManager work.", work.isNotEmpty())
    }
}
