package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraJsonTaskPlanDecoderTest {
    private val request = GlafiraTaskPlanRequest(
        instruction = "Read and summarize project materials",
        projectId = "project-a",
        idempotencyKey = "instruction-0001",
        createdAtMillis = 1_000L
    )

    @Test fun decodesIndependentBranchesAndDerivesScopesFromTrustedRegistry() {
        val json = """{
            "planId":"plan-00000001",
            "nodes":[
                {"taskId":"root","taskType":"project.read","payload":"{}"},
                {"taskId":"branch-a","taskType":"project.read","payload":"{}","dependsOnTaskIds":["root"],"requiredScopes":["admin.root"]},
                {"taskId":"branch-b","taskType":"project.read","payload":"{}","dependsOnTaskIds":["root"]},
                {"taskId":"join","taskType":"conversation.read","payload":"{}","dependsOnTaskIds":["branch-a","branch-b"]}
            ]
        }"""
        val plan = GlafiraJsonTaskPlanDecoder.decode(
            request, json, setOf("project.read", "conversation.read")
        )
        assertEquals(4, plan.nodes.size)
        assertEquals(setOf("project.read"), plan.nodes[1].task.requiredScopes)
        assertEquals(setOf("branch-a", "branch-b"), plan.nodes.last().dependsOnTaskIds)
    }

    @Test fun rejectsTaskTypesOutsideAllowlistOrRegistry() {
        val json = """{"planId":"plan-00000002","nodes":[
            {"taskId":"root","taskType":"system.shell","payload":"{}"}
        ]}"""
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, json, setOf("project.read"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, json, setOf("system.shell"))
        }
    }

    @Test fun rejectsMalformedPayloadsAndDependencyCycles() {
        val malformed = """{"planId":"plan-00000003","nodes":[
            {"taskId":"root","taskType":"project.read","payload":"not-json"}
        ]}"""
        assertThrows(Exception::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, malformed, setOf("project.read"))
        }
        val cyclic = """{"planId":"plan-00000004","nodes":[
            {"taskId":"a","taskType":"project.read","payload":"{}","dependsOnTaskIds":["b"]},
            {"taskId":"b","taskType":"project.read","payload":"{}","dependsOnTaskIds":["a"]}
        ]}"""
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraJsonTaskPlanDecoder.decode(request, cyclic, setOf("project.read"))
        }
    }
}
