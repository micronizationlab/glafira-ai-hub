package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject

/** Strict, side-effect-free task plan accepted by GLAFIRA Core. */
internal enum class GlafiraTaskExecutor { GLAFIRA, ANALYST, PROGRAMMER, RESEARCHER }

internal data class GlafiraSubtask(
    val id: String,
    val title: String,
    val instruction: String,
    val executor: GlafiraTaskExecutor,
    val dependsOn: List<String>
)

internal data class GlafiraTaskPlan(val goal: String, val tasks: List<GlafiraSubtask>)

internal object GlafiraTaskPlanner {
    const val MAX_TASKS = 4
    const val MAX_PARALLEL_TASKS = 2
    private const val MAX_TEXT_LENGTH = 4_000

    fun parseAndValidate(rawJson: String): GlafiraTaskPlan {
        require(rawJson.length <= 32_000) { "План слишком большой." }
        val json = JSONObject(rawJson)
        val goal = json.optString("goal").trim()
        require(goal.isNotBlank() && goal.length <= MAX_TEXT_LENGTH) {
            "В плане отсутствует корректная цель."
        }
        val source = json.optJSONArray("tasks") ?: error("В плане отсутствует список задач.")
        require(source.length() in 1..MAX_TASKS) {
            "План должен содержать от 1 до $MAX_TASKS подзадач."
        }

        val tasks = ArrayList<GlafiraSubtask>(source.length())
        val ids = mutableSetOf<String>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: error("Подзадача ${index + 1} имеет неверный формат.")
            val id = item.optString("id").trim()
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) { "Некорректный идентификатор подзадачи." }
            require(ids.add(id)) { "Идентификатор подзадачи повторяется: $id" }

            val title = item.optString("title").trim()
            val instruction = item.optString("instruction").trim()
            require(title.isNotBlank() && title.length <= 160) { "У подзадачи $id некорректное название." }
            require(instruction.isNotBlank() && instruction.length <= MAX_TEXT_LENGTH) {
                "У подзадачи $id отсутствует корректное задание."
            }
            val executor = runCatching {
                GlafiraTaskExecutor.valueOf(item.optString("executor").trim().uppercase())
            }.getOrElse { error("У подзадачи $id неизвестный исполнитель.") }
            val dependencies = parseDependencies(item.optJSONArray("dependsOn") ?: JSONArray())
            require(id !in dependencies) { "Подзадача $id не может зависеть от самой себя." }
            tasks += GlafiraSubtask(id, title, instruction, executor, dependencies)
        }

        val knownIds = tasks.map { it.id }.toSet()
        tasks.forEach { task ->
            require(task.dependsOn.distinct().size == task.dependsOn.size) {
                "В подзадаче ${task.id} повторяется зависимость."
            }
            task.dependsOn.forEach { dependency ->
                require(dependency in knownIds) {
                    "Подзадача ${task.id} зависит от неизвестной задачи $dependency."
                }
            }
        }
        validateAcyclic(tasks)
        return GlafiraTaskPlan(goal, tasks)
    }

    /** Build executable batches with at most two independent tasks per batch. */
    fun readyBatches(plan: GlafiraTaskPlan, completedIds: Set<String> = emptySet()): List<List<GlafiraSubtask>> {
        val completed = completedIds.toMutableSet()
        val tasksById = plan.tasks.associateBy { it.id }
        require(completed.all { id -> id in tasksById }) {
            "Список завершённых задач содержит неизвестный идентификатор."
        }
        require(completed.all { id -> tasksById.getValue(id).dependsOn.all { it in completed } }) {
            "Нельзя отметить задачу завершённой до завершения всех её зависимостей."
        }
        val remaining = plan.tasks.filterNot { it.id in completed }.toMutableList()
        val batches = mutableListOf<List<GlafiraSubtask>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { task -> task.dependsOn.all { it in completed } }
                .take(MAX_PARALLEL_TASKS)
            check(ready.isNotEmpty()) { "План не может быть выполнен: обнаружена циклическая зависимость." }
            batches += ready
            val ids = ready.map { it.id }.toSet()
            completed += ids
            remaining.removeAll { it.id in ids }
        }
        return batches
    }

    private fun parseDependencies(array: JSONArray): List<String> = buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            require(value.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) {
                "Некорректный идентификатор зависимости."
            }
            add(value)
        }
    }

    private fun validateAcyclic(tasks: List<GlafiraSubtask>) {
        val byId = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "В плане обнаружена циклическая зависимость." }
            byId.getValue(id).dependsOn.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        tasks.forEach { visit(it.id) }
    }
}
