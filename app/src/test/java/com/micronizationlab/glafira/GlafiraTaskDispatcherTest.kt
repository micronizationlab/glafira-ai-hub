package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraTaskDispatcherTest {
    private fun plan(): GlafiraTaskPlan = GlafiraTaskPlanner.parseAndValidate(
        """{"goal":"Prepare report","tasks":[
            {"id":"research","title":"Research","instruction":"Find facts","executor":"RESEARCHER","dependsOn":[]},
            {"id":"analysis","title":"Analyze","instruction":"Analyze facts","executor":"ANALYST","dependsOn":["research"]},
            {"id":"final","title":"Synthesize","instruction":"Prepare report","executor":"GLAFIRA","dependsOn":["analysis"]}
        ]}"""
    )

    @Test
    fun dispatchesTasksInDependencyOrderAndPassesDependencyOutput() = runBlocking {
        val seen = mutableListOf<String>()
        val report = GlafiraTaskDispatcher.execute(
            plan(),
            mapOf(
                GlafiraTaskExecutor.RESEARCHER to GlafiraTaskHandler { _, _ ->
                    seen += "research"
                    "sources"
                },
                GlafiraTaskExecutor.ANALYST to GlafiraTaskHandler { _, dependencies ->
                    seen += "analysis"
                    "analysis:${dependencies.getValue("research")}"
                },
                GlafiraTaskExecutor.GLAFIRA to GlafiraTaskHandler { _, dependencies ->
                    seen += "glafira"
                    "final:${dependencies.getValue("analysis")}"
                }
            )
        )

        assertEquals(listOf("research", "analysis", "glafira"), seen)
        assertTrue(report.succeeded)
        assertEquals("final:analysis:sources", report.results.last().output)
    }

    @Test
    fun missingHandlerFailsClosedAndSkipsDependentTasks() = runBlocking {
        val report = GlafiraTaskDispatcher.execute(plan(), emptyMap())
        assertFalse(report.succeeded)
        assertEquals(GlafiraTaskStatus.FAILED, report.results[0].status)
        assertEquals(GlafiraTaskStatus.SKIPPED, report.results[1].status)
        assertEquals(GlafiraTaskStatus.SKIPPED, report.results[2].status)
    }

    @Test
    fun handlerFailureDoesNotRunLaterDependencyLayers() = runBlocking {
        var laterTaskRan = false
        val report = GlafiraTaskDispatcher.execute(
            plan(),
            mapOf(
                GlafiraTaskExecutor.RESEARCHER to GlafiraTaskHandler { _, _ -> error("research unavailable") },
                GlafiraTaskExecutor.ANALYST to GlafiraTaskHandler { _, _ ->
                    laterTaskRan = true
                    "should not run"
                }
            )
        )
        assertFalse(report.succeeded)
        assertEquals(GlafiraTaskStatus.FAILED, report.results[0].status)
        assertEquals(GlafiraTaskStatus.SKIPPED, report.results[1].status)
        assertFalse(laterTaskRan)
    }

    @Test
    fun blankHandlerOutputIsNotReportedAsSuccess() = runBlocking {
        val report = GlafiraTaskDispatcher.execute(
            plan(),
            mapOf(GlafiraTaskExecutor.RESEARCHER to GlafiraTaskHandler { _, _ -> " " })
        )
        assertEquals(GlafiraTaskStatus.FAILED, report.results[0].status)
    }
}
