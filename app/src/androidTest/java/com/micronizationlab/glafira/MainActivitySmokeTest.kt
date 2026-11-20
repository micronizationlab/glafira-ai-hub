package com.micronizationlab.glafira

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun mainActivityLaunchesAndRemainsForegrounded() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse("MainActivity unexpectedly finished", activity.isFinishing)
                assertFalse("MainActivity unexpectedly destroyed", activity.isDestroyed)
            }
        } finally {
            scenario.close()
        }
    }
}
