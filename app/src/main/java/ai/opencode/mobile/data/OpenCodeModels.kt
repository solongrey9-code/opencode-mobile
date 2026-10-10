package ai.opencode.mobile.data

import kotlinx.serialization.json.JsonObject

data class ConnectionProfile(
    val baseUrl: String = "http://127.0.0.1:4096",
    val bridgeBaseUrl: String = "",
    val bridgeToken: String = "",
    val directory: String = "",
    val username: String = "opencode",
    val password: String = "",
)

data class ServerHealth(
    val healthy: Boolean,
    val version: String,
)

data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
)

data class SessionModel(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)

data class OpenCodeSession(
    val id: String,
    val title: String,
    val directory: String,
    val projectId: String,
    val parentId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val summary: SessionSummary? = null,
    val shareUrl: String? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
)

data class SessionStatus(
    val type: Type,
    val attempt: Int? = null,
    val message: String? = null,
    val nextAt: Long? = null,
) {
    enum class Type { IDLE, BUSY, RETRY }
}

data class MessageInfo(
    val id: String,
    val sessionId: String,
    val role: Role,
    val createdAt: Long,
    val completedAt: Long? = null,
    val parentId: String? = null,
    val agent: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val cost: Double? = null,
    val errorName: String? = null,
    val errorMessage: String? = null,
) {
    enum class Role { USER, ASSISTANT, UNKNOWN }
}

data class MessagePart(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val type: String,
    val text: String? = null,
    val filename: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val toolTitle: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolError: String? = null,
    val files: List<String> = emptyList(),
    val reason: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val raw: JsonObject,
)

data class SessionMessage(
    val info: MessageInfo,
    val parts: List<MessagePart>,
)

data class OpenCodeTodo(
    val content: String,
    val status: String,
    val priority: String,
)

data class FileDiff(
    val file: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val status: String? = null,
)

data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String>,
    val always: List<String>,
    val metadata: String,
    val directory: String? = null,
)

data class QuestionOption(
    val label: String,
    val description: String,
)

data class OpenCodeQuestion(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean,
    val custom: Boolean,
)

data class QuestionRequest(
    val id: String,
    val sessionId: String,
    val questions: List<OpenCodeQuestion>,
    val directory: String? = null,
)

data class AgentChoice(
    val name: String,
    val description: String = "",
    val mode: String = "",
    val model: SessionModel? = null,
)

data class ModelChoice(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val providerName: String = providerId,
    val modelName: String = modelId,
    val family: String = "",
    val releaseDate: String = "",
    val latest: Boolean = false,
) {
    val key: String get() = "$providerId/$modelId"
}

data class ModelCatalog(
    val models: List<ModelChoice>,
    val defaultModelKey: String? = null,
)

data class DesktopModelPreference(
    val providerId: String,
    val modelId: String,
    val visibility: String,
)

data class DesktopBridgeState(
    val desktopUsesSharedServer: Boolean = false,
    val defaultServerUrl: String? = null,
    val privateOpenSessionIds: Set<String> = emptySet(),
    val sharedOpenSessionIds: Set<String> = emptySet(),
    val privateRecentSessionId: String? = null,
    val sharedRecentSessionId: String? = null,
    val serverUrls: List<String> = emptyList(),
    val modelPreferences: List<DesktopModelPreference> = emptyList(),
    val recentModelKeys: List<String> = emptyList(),
)

data class ServerEvent(
    val id: String?,
    val type: String,
    val properties: JsonObject,
    val directory: String? = null,
)

data class SessionDetail(
    val loading: Boolean = false,
    val messages: List<SessionMessage> = emptyList(),
    val todos: List<OpenCodeTodo> = emptyList(),
    val diffs: List<FileDiff> = emptyList(),
)
