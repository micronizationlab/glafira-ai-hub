package com.micronizationlab.glafira

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidContactsReaderInstrumentedTest {
    @Test
    fun readContactsFailsClosedWhenRuntimePermissionIsAbsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.revokeRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CONTACTS
        )

        val result = AndroidContactsReader(context).readContacts()

        assertTrue(result is AndroidContactsReadResult.PermissionRequired)
    }
}
