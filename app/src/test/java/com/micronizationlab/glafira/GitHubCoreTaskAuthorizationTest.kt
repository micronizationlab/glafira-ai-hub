package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCoreTaskAuthorizationTest {
    private class Grants : GlafiraTaskGrantSource {
        var scopes: Set<String> = emptySet()
        var grant: ToolApprovalGrant? = null
        var approvalRequested = false

        override fun grantedScopes(task: GlafiraTask): Set<String> = scopes
        override fun approvalGrant(
            task: GlafiraTask,
            actionSummary: String,
            nowMillis: Long
        ): ToolApprovalGrant? = grant

        override fun requestApproval(
            task: GlafiraTask,
            actionSummary: String,
            nowMillis: Long
        ): Boolean {
            approvalRequested = true
            return true
        }
    }

    private fun task(toolId: String) = GlafiraTask(
        taskId = "github-task-001",
        projectId = "project-owner-001",
        taskType = toolId,
        payload = """{"actionSummary":"Read or modify the approved GLAFIRA repository"}""",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "github-idem-001"
    )

    @Test
    fun allGitHubToolsAreRegisteredWithNarrowScopes() {
        val expected = mapOf(
            "github.repository.tree" to ("github.repo.read" to ToolRiskLevel.READ_ONLY),
            "github.file.read" to ("github.repo.read" to ToolRiskLevel.READ_ONLY),
            "github.branch.create" to ("github.branch.write" to ToolRiskLevel.EXTERNAL_WRITE),
            "github.file.write" to ("github.contents.write" to ToolRiskLevel.EXTERNAL_WRITE),
            "github.pull_request.draft" to ("github.pull_request.write" to ToolRiskLevel.EXTERNAL_WRITE)
        )

        expected.forEach { (toolId, contract) ->
            val definition = GlafiraToolRegistry.find(toolId)
            assertTrue("Missing tool $toolId", definition != null)
            assertEquals(setOf(contract.first), definition!!.requiredScopes)
            assertEquals(contract.second, definition.risk)
            assertTrue(GlafiraToolRegistry.isKnownScope(contract.first))
        }
        assertFalse(GlafiraToolRegistry.all().any { it.id == "github.merge" })
    }

    @Test
    fun providerOutputCannotReplaceMissingOwnerScopeOrApproval() {
        val githubTask = task("github.file.write")
        val grants = Grants()
        val authorizer = RegistryBackedGlafiraTaskAuthorizer(grants)

        val denied = authorizer.authorize(githubTask, 2_000L)
        assertEquals(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER, denied.status)
        assertTrue(grants.approvalRequested)
    }

    @Test
    fun exactOwnerGrantAuthorizesOnlyTheBoundGitHubWriteTask() {
        val githubTask = task("github.file.write")
        val actionSummary = "Read or modify the approved GLAFIRA repository"
        val grants = Grants().apply {
            scopes = setOf("github.contents.write")
            grant = ToolApprovalGrant(
                projectId = githubTask.projectId,
                toolId = githubTask.taskType,
                actionHash = GlafiraToolRegistry.actionHash(githubTask, actionSummary),
                approvedAt = 1_500L,
                expiresAt = 5_000L,
                taskId = githubTask.taskId
            )
        }
        val authorizer = RegistryBackedGlafiraTaskAuthorizer(grants)

        assertEquals(GlafiraTaskAuthorizationStatus.AUTHORIZED, authorizer.authorize(githubTask, 2_000L).status)
        val changed = githubTask.copy(payload = """{"actionSummary":"Write a different file"}""")
        assertEquals(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER, authorizer.authorize(changed, 2_000L).status)
    }

    @Test
    fun providerIdentityIsNotPartOfToolAuthorizationContract() {
        // Core authorizes the typed task using only owner grants, registered scopes and action hash.
        // There is deliberately no model/provider parameter in this authorization boundary.
        val task = task("github.repository.tree")
        val grants = Grants().apply { scopes = setOf("github.repo.read") }
        assertEquals(
            GlafiraTaskAuthorizationStatus.AUTHORIZED,
            RegistryBackedGlafiraTaskAuthorizer(grants).authorize(task, 2_000L).status
        )
    }
}
