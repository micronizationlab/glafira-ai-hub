package com.micronizationlab.glafira

import kotlinx.coroutines.CancellationException

internal data class GlafiraToolRequest(
    val toolId: String,
    val projectId: String,
    val actionSummary: String,
    val payload: String
)

internal fun interface GlafiraToolHandler {
    suspend fun execute(request: GlafiraToolRequest): String
}

internal enum class GlafiraToolCallStatus {
    SUCCEEDED, UNKNOWN_TOOL, MISSING_SCOPE, OWNER_APPROVAL_REQUIRED,
    HANDLER_NOT_REGISTERED, FAILED
}

internal data class GlafiraToolCallResult(
    val status: GlafiraToolCallStatus,
    val output: String? = null,
    val message: String? = null
)

/**
 * Fail-closed tool broker. Scopes and owner grants must come from trusted app-side
 * state; never populate either from model-generated arguments.
 */
internal object GlafiraToolBroker {
    suspend fun invoke(
        request: GlafiraToolRequest,
        grantedScopes: Set<String>,
        handlers: Map<String, GlafiraToolHandler>,
        approvalGrant: ToolApprovalGrant? = null,
        now: Long = System.currentTimeMillis()
    ): GlafiraToolCallResult {
        if (request.projectId.isBlank() || request.projectId.length > 128 ||
            request.actionSummary.isBlank() || request.actionSummary.length > MAX_SUMMARY_CHARS ||
            request.payload.length > MAX_PAYLOAD_CHARS
        ) return GlafiraToolCallResult(
            GlafiraToolCallStatus.FAILED,
            message = "Некорректный или слишком большой запрос инструмента."
        )

        val definition = GlafiraToolRegistry.find(request.toolId)
            ?: return GlafiraToolCallResult(
                GlafiraToolCallStatus.UNKNOWN_TOOL,
                message = "Инструмент не зарегистрирован."
            )

        if (!grantedScopes.containsAll(definition.requiredScopes)) {
            return GlafiraToolCallResult(
                GlafiraToolCallStatus.MISSING_SCOPE,
                message = "Не выданы необходимые полномочия."
            )
        }

        if (GlafiraToolRegistry.authorize(
                definition, request.projectId, request.actionSummary, approvalGrant, now
            ) != ToolAuthorizationDecision.ALLOWED
        ) return GlafiraToolCallResult(
            GlafiraToolCallStatus.OWNER_APPROVAL_REQUIRED,
            message = "Для этого действия требуется подтверждение владельца."
        )

        val handler = handlers[definition.id]
            ?: return GlafiraToolCallResult(
                GlafiraToolCallStatus.HANDLER_NOT_REGISTERED,
                message = "Исполнитель инструмента ещё не подключён."
            )

        return try {
            val output = handler.execute(request)
            if (output.isBlank()) GlafiraToolCallResult(
                GlafiraToolCallStatus.FAILED,
                message = "Инструмент вернул пустой результат."
            ) else GlafiraToolCallResult(
                GlafiraToolCallStatus.SUCCEEDED,
                output = output.take(MAX_OUTPUT_CHARS)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GlafiraToolCallResult(
                GlafiraToolCallStatus.FAILED,
                message = (error.message ?: "Ошибка выполнения инструмента").take(MAX_ERROR_CHARS)
            )
        }
    }

    private const val MAX_SUMMARY_CHARS = 1_000
    private const val MAX_PAYLOAD_CHARS = 64_000
    private const val MAX_OUTPUT_CHARS = 24_000
    private const val MAX_ERROR_CHARS = 500
}
