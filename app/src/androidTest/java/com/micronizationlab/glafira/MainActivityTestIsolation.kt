package com.micronizationlab.glafira

import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice

/** Ensures owner-visible UI smoke tests never inherit another test's Activity/Compose state. */
internal fun forceStopTargetApp(instrumentation: Instrumentation) {
    val packageName = instrumentation.targetContext.packageName
    require(packageName.matches(Regex("^[a-zA-Z0-9._]+$")))
    UiDevice.getInstance(instrumentation).executeShellCommand("am force-stop $packageName")
    instrumentation.waitForIdleSync()
}
