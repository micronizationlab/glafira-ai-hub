package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraJsonTaskPlanDecoderTest {
    private val request = GlafiraCoreTaskPlanRequest(
        instruction = "Read and summarize project materials",
        projectId = "project-a",
        idempotencyKey = "instruction-0001",
        createdAtMillis = 1_000L
    )

    @Test fun reusesExistingPlannerSchemaAndDerivesScopesFromTrustedToolRegistry() {
        val json = """{
            "goal":"Research and summarize project materials",
            "tasks":[
                {"id":"root","title":"Read source material","instruction":"Read the project source material","executor":"GLAFIRA","dependsOn":[]},
                {"id":"branch_a","title":"Analyze","instruction":"Analyze the source material","executor":"ANALYST","dependsOn":["root"],"requiredScopes":["admin.root"]},
                {"id":"branch_b","title":"Research","instruction":"Research supporting sources","executor":"RESEARCHER","dependsOn":["root"]},
                {"id":"join","title":"Synthesize","instruction":"Combine both branch results","executor":"GLAFIRA","dependsOn":["branch_a","branch_b"]}
            ]
        }"""
        val plan = GlafiraJsonTaskPlanDecoder.decode(request, json)
        assertEquals(4, plan.nodes.size)
        assertEquals(setOf("desktop.task.execute"), plan.nodes[1].task.requiredScopes)
        assertEquals(setOf(plan.nodes[1].task.taskId, plan.nodes[2].task.taskId),
            plan.nodes.last().dependsOnTaskIds)
        assertEquals(plan.planId, GlafiraCoreTaskPlanAdapter.toPlannerPlan(plan).let { 
            GlafiraCoreTaskPlanAdapter.fromPlannerPlan(request, it).planId
        })
    }

    @Test fun rejectsUnknownExecutorAndMalformedPlannerPayloads() {
        val unknown = """{"goal":"Do work","tasks":[
            {"id":"root","title":"Run shell","instruction":"Run arbitrary shell commands","executor":"SYSTEM","dependsOn":[]}
        ]}"""
        assertThrows(IllegalStateException::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, unknown)
        }
        assertThrows(Exception::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, "not-json")
        }
    }

    @Test fun rejectsDependencyCyclesAndUnknownDependencies() {
        val cyclic = """{"goal":"Do work","tasks":[
            {"id":"a","title":"A","instruction":"Task A","executor":"GLAFIRA","dependsOn":["b"]},
            {"id":"b","title":"B","instruction":"Task B","executor":"ANALYST","dependsOn":["a"]}
        ]}"""
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, cyclic)
        }
        val unknownDependency = """{"goal":"Do work","tasks":[
            {"id":"a","title":"A","instruction":"Task A","executor":"GLAFIRA","dependsOn":["outside"]}
        ]}"""
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, unknownDependency)
        }
    }
}
