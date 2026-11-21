package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlafiraProjectChatContextTest {
    @get:Rule val temporary = TemporaryFolder()
    private val histories = mutableMapOf<String, List<Msg>>()

    @Test fun previewIsProjectScopedAndExcludesCurrentAgent() {
        chatFile("project-1", "Юрист", "law")
        chatFile("project-1", "ГЛАФИРА", "current")
        chatFile("project-2", "Финансист", "foreign")
        histories["project-1_Юрист"] = listOf(message("project one evidence"))
        histories["project-1_ГЛАФИРА"] = listOf(message("current agent content"))
        histories["project-2_Финансист"] = listOf(message("foreign project content"))

        val preview = assembler().preview(
            ProjectIdentifier("project-1"),
            currentAgentName = "ГЛАФИРА",
            recipient = "Primary"
        )

        assertEquals(listOf("Юрист"), preview.citations.map { it.agentName })
        assertTrue(preview.citations.single().excerpt.contains("project one evidence"))
        assertFalse(preview.renderForProvider().contains("foreign project"))
        assertFalse(preview.renderForProvider().contains("current agent"))
    }

    @Test fun exactOwnerAllowlistRedactsSecretsAndOmitsOtherChats() {
        chatFile("project-1", "Юрист", "law")
        chatFile("project-1", "Финансист", "finance")
        histories["project-1_Юрист"] = listOf(message("contract password=HiddenSecret"))
        histories["project-1_Финансист"] = listOf(message("private forecast nine"))
        val all = assembler().preview(ProjectIdentifier("project-1"), "ГЛАФИРА", "Primary")
        val legal = all.citations.single { it.agentName == "Юрист" }.source

        val approved = assembler().preview(
            ProjectIdentifier("project-1"),
            currentAgentName = "ГЛАФИРА",
            recipient = "Primary",
            includedSources = setOf(legal)
        )
        val prompt = approved.renderForProvider()

        assertEquals(listOf("Юрист"), approved.citations.map { it.agentName })
        assertTrue(prompt.contains("[СКРЫТО]"))
        assertFalse(prompt.contains("HiddenSecret"))
        assertFalse(prompt.contains("private forecast"))
    }

    @Test fun changedChatInvalidatesPreviouslyApprovedVersion() {
        val file = chatFile("project-1", "Юрист", "first")
        histories["project-1_Юрист"] = listOf(message("first version"))
        val approvedRef = assembler().preview(
            ProjectIdentifier("project-1"), "ГЛАФИРА", "Primary"
        ).citations.single().source

        file.writeText("second")
        histories["project-1_Юрист"] = listOf(message("second version"))
        val afterChange = assembler().preview(
            ProjectIdentifier("project-1"),
            currentAgentName = "ГЛАФИРА",
            recipient = "Primary",
            includedSources = setOf(approvedRef)
        )

        assertTrue(afterChange.citations.isEmpty())
        assertEquals("", afterChange.renderForProvider())
    }

    @Test fun previewHonorsSourceAndTotalBudgets() {
        repeat(3) { index ->
            chatFile("project-1", "Agent-$index", "source-$index")
            histories["project-1_Agent-$index"] = listOf(message("bounded ".repeat(200)))
        }

        val preview = assembler().preview(
            ProjectIdentifier("project-1"),
            currentAgentName = "ГЛАФИРА",
            recipient = "Primary",
            maxSources = 2,
            contextBudgetChars = 256
        )

        assertTrue(preview.citations.size <= 2)
        assertTrue(preview.usedContextChars <= 256)
        assertEquals(preview.citations.sumOf { it.excerpt.length }, preview.usedContextChars)
    }

    private fun assembler() = ProjectChatContextAssembler(temporary.root) { histories[it].orEmpty() }

    private fun chatFile(project: String, agent: String, content: String) =
        temporary.root.resolve("chat_${project}_${agent}.json").apply { writeText(content) }

    private fun message(text: String) = Msg(text, isUser = true, time = "10:00")
}
