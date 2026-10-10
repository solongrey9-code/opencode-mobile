package ai.opencode.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object OpenCodeJson {
    val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseHealth(value: String): ServerHealth {
        val root = objectValue(value)
        return ServerHealth(
            healthy = root.boolean("healthy") ?: false,
            version = root.string("version").orEmpty(),
        )
    }

    fun parseSessions(value: String): List<OpenCodeSession> =
        arrayValue(value).mapNotNull { element ->
            (element as? JsonObject)?.let(::parseSession)
        }

    fun parseSession(value: String): OpenCodeSession =
        parseSession(objectValue(value)) ?: error("OpenCode returned an invalid session")

    fun parseSession(value: JsonObject): OpenCodeSession? {
        val id = value.string("id") ?: return null
        val time = value.objectOrNull("time")
        val summaryObject = value.objectOrNull("summary")
        val modelObject = value.objectOrNull("model")
        return OpenCodeSession(
            id = id,
            title = value.string("title").orEmpty().ifBlank { id },
            directory = value.string("directory").orEmpty(),
            projectId = value.string("projectID").orEmpty(),
            parentId = value.string("parentID"),
            createdAt = time?.long("created") ?: 0L,
            updatedAt = time?.long("updated") ?: 0L,
            summary = summaryObject?.let {
                SessionSummary(
                    additions = it.int("additions") ?: 0,
                    deletions = it.int("deletions") ?: 0,
                    files = it.int("files") ?: 0,
                )
            },
            shareUrl = value.objectOrNull("share")?.string("url"),
            agent = value.string("agent"),
            model = modelObject?.let {
                val providerId = it.string("providerID") ?: return@let null
                val modelId = it.string("id") ?: it.string("modelID") ?: return@let null
                SessionModel(providerId, modelId, it.string("variant"))
            },
        )
    }

    fun parseStatuses(value: String): Map<String, SessionStatus> =
        objectValue(value).mapNotNull { (sessionId, status) ->
            (status as? JsonObject)?.let { sessionId to parseStatus(it) }
        }.toMap()

    fun parseStatus(value: JsonObject): SessionStatus {
        return when (value.string("type")) {
            "busy" -> SessionStatus(SessionStatus.Type.BUSY)
            "retry" -> SessionStatus(
                type = SessionStatus.Type.RETRY,
                attempt = value.int("attempt"),
                message = value.string("message"),
                nextAt = value.long("next"),
            )
            else -> SessionStatus(SessionStatus.Type.IDLE)
        }
    }

    fun parseMessages(value: String): List<SessionMessage> =
        arrayValue(value).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val info = item.objectOrNull("info")?.let(::parseMessageInfo) ?: return@mapNotNull null
            val parts = item.arrayOrNull("parts").orEmpty().mapNotNull { part ->
                (part as? JsonObject)?.let(::parsePart)
            }
            SessionMessage(info, parts)
        }

    fun parseMessageInfo(value: JsonObject): MessageInfo? {
        val id = value.string("id") ?: return null
        val sessionId = value.string("sessionID") ?: return null
        val role = when (value.string("role")) {
            "user" -> MessageInfo.Role.USER
            "assistant" -> MessageInfo.Role.ASSISTANT
            else -> MessageInfo.Role.UNKNOWN
        }
        val time = value.objectOrNull("time")
        val model = value.objectOrNull("model")
        val error = value.objectOrNull("error")
        val errorData = error?.objectOrNull("data")
        return MessageInfo(
            id = id,
            sessionId = sessionId,
            role = role,
            createdAt = time?.long("created") ?: 0L,
            completedAt = time?.long("completed"),
            parentId = value.string("parentID"),
            agent = value.string("agent"),
            providerId = value.string("providerID") ?: model?.string("providerID"),
            modelId = value.string("modelID") ?: model?.string("modelID"),
            variant = value.string("variant") ?: model?.string("variant"),
            cost = value.double("cost"),
            errorName = error?.string("name"),
            errorMessage = errorData?.string("message") ?: error?.string("message"),
        )
    }

    fun parsePart(value: JsonObject): MessagePart? {
        val id = value.string("id") ?: return null
        val sessionId = value.string("sessionID") ?: return null
        val messageId = value.string("messageID") ?: return null
        val type = value.string("type") ?: return null
        val state = value.objectOrNull("state")
        val time = value.objectOrNull("time") ?: state?.objectOrNull("time")
        return MessagePart(
            id = id,
            sessionId = sessionId,
            messageId = messageId,
            type = type,
            text = value.string("text"),
            filename = value.string("filename"),
            mime = value.string("mime"),
            url = value.string("url"),
            toolName = value.string("tool"),
            toolStatus = state?.string("status"),
            toolTitle = state?.string("title"),
            toolInput = state?.get("input")?.compactString(),
            toolOutput = state?.string("output"),
            toolError = state?.string("error"),
            files = value.arrayOrNull("files").orEmpty().mapNotNull { it.primitiveContent() },
            reason = value.string("reason"),
            startedAt = time?.long("start"),
            endedAt = time?.long("end"),
            raw = value,
        )
    }

    fun parseTodos(value: String): List<OpenCodeTodo> = parseTodos(arrayValue(value))

    fun parseTodos(value: JsonArray): List<OpenCodeTodo> = value.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val content = item.string("content") ?: return@mapNotNull null
        OpenCodeTodo(
            content = content,
            status = item.string("status").orEmpty(),
            priority = item.string("priority").orEmpty(),
        )
    }

    fun parseDiffs(value: String): List<FileDiff> = parseDiffs(arrayValue(value))

    fun parseDiffs(value: JsonArray): List<FileDiff> = value.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val file = item.string("file") ?: item.string("path") ?: return@mapNotNull null
        FileDiff(
            file = file,
            patch = item.string("patch").orEmpty(),
            additions = item.int("additions") ?: 0,
            deletions = item.int("deletions") ?: 0,
            status = item.string("status"),
        )
    }

    fun parsePermissions(value: String, directory: String? = null): List<PermissionRequest> =
        arrayValue(value).mapNotNull { element ->
            (element as? JsonObject)?.let { parsePermission(it, directory) }
        }

    fun parsePermission(value: JsonObject, directory: String? = null): PermissionRequest? {
        val id = value.string("id") ?: return null
        val sessionId = value.string("sessionID") ?: return null
        return PermissionRequest(
            id = id,
            sessionId = sessionId,
            permission = value.string("permission") ?: value.string("action").orEmpty(),
            patterns = (value.arrayOrNull("patterns") ?: value.arrayOrNull("resources"))
                .orEmpty().mapNotNull { it.primitiveContent() },
            always = (value.arrayOrNull("always") ?: value.arrayOrNull("save"))
                .orEmpty().mapNotNull { it.primitiveContent() },
            metadata = value.objectOrNull("metadata")?.compactString().orEmpty(),
            directory = directory,
        )
    }

    fun parseQuestions(value: String, directory: String? = null): List<QuestionRequest> =
        arrayValue(value).mapNotNull { element ->
            (element as? JsonObject)?.let { parseQuestionRequest(it, directory) }
        }

    fun parseQuestionRequest(value: JsonObject, directory: String? = null): QuestionRequest? {
        val id = value.string("id") ?: return null
        val sessionId = value.string("sessionID") ?: return null
        val questions = value.arrayOrNull("questions").orEmpty().mapNotNull { element ->
            val question = element as? JsonObject ?: return@mapNotNull null
            OpenCodeQuestion(
                question = question.string("question").orEmpty(),
                header = question.string("header").orEmpty(),
                options = question.arrayOrNull("options").orEmpty().mapNotNull { optionElement ->
                    val option = optionElement as? JsonObject ?: return@mapNotNull null
                    QuestionOption(
                        label = option.string("label") ?: return@mapNotNull null,
                        description = option.string("description").orEmpty(),
                    )
                },
                multiple = question.boolean("multiple") ?: false,
                custom = question.boolean("custom") ?: true,
            )
        }
        return QuestionRequest(id, sessionId, questions, directory)
    }

    fun parseAgents(value: String): List<AgentChoice> = arrayValue(value).mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.string("name") ?: return@mapNotNull null
        AgentChoice(
            name = name,
            description = item.string("description").orEmpty(),
            mode = item.string("mode").orEmpty(),
            model = item.objectOrNull("model")?.let { model ->
                val providerId = model.string("providerID") ?: return@let null
                val modelId = model.string("modelID") ?: model.string("id") ?: return@let null
                SessionModel(providerId, modelId, item.string("variant"))
            },
        )
    }

    fun parseModels(value: String, configValue: String? = null): ModelCatalog {
        val root = objectValue(value)
        val providers = root.arrayOrNull("all") ?: root.arrayOrNull("providers") ?: JsonArray(emptyList())
        val connected = root.arrayOrNull("connected")
            ?.mapNotNull { it.primitiveContent() }
            ?.toSet()
        val models = providers.flatMap { providerElement ->
            val provider = providerElement as? JsonObject ?: return@flatMap emptyList()
            val providerId = provider.string("id") ?: return@flatMap emptyList()
            if (connected != null && providerId !in connected) return@flatMap emptyList()
            val providerName = provider.string("name") ?: providerId
            val providerModels = provider.objectOrNull("models") ?: return@flatMap emptyList()
            providerModels.mapNotNull { (modelId, modelElement) ->
                val model = modelElement as? JsonObject ?: return@mapNotNull null
                if (model.string("status") == "deprecated") return@mapNotNull null
                val rawName = model.string("name") ?: modelId
                val modelName = rawName.replace("(latest)", "").trim()
                ModelChoice(
                    providerId = providerId,
                    modelId = modelId,
                    displayName = "$providerName / $modelName",
                    providerName = providerName,
                    modelName = modelName,
                    family = model.string("family").orEmpty(),
                    releaseDate = model.string("release_date").orEmpty(),
                    latest = rawName.contains("(latest)"),
                )
            }
        }
        val configuredParts = configValue?.split('/', limit = 3).orEmpty()
        val configured = configuredParts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { providerId ->
            val modelId = configuredParts.getOrNull(1).orEmpty()
            if (modelId.isBlank()) null else "$providerId/$modelId"
        }
        val providerDefaults = root.objectOrNull("default")
        val fallback = connected.orEmpty().firstNotNullOfOrNull { providerId ->
            providerDefaults?.string(providerId)?.let { "$providerId/$it" }
        }
        return ModelCatalog(models, configured ?: fallback)
    }

    fun parseDesktopBridge(value: String): DesktopBridgeState {
        val root = objectValue(value)
        val desktop = root.objectOrNull("desktop") ?: JsonObject(emptyMap())
        val network = root.objectOrNull("network") ?: JsonObject(emptyMap())
        val models = root.objectOrNull("models") ?: JsonObject(emptyMap())
        return DesktopBridgeState(
            desktopUsesSharedServer = desktop.boolean("desktopUsesSharedServer") ?: false,
            defaultServerUrl = desktop.string("defaultServerUrl"),
            privateOpenSessionIds = desktop.arrayOrNull("privateOpenSessionIds").orEmpty()
                .mapNotNull { it.primitiveContent() }.toSet(),
            sharedOpenSessionIds = desktop.arrayOrNull("sharedOpenSessionIds").orEmpty()
                .mapNotNull { it.primitiveContent() }.toSet(),
            privateRecentSessionId = desktop.string("privateRecentSessionId"),
            sharedRecentSessionId = desktop.string("sharedRecentSessionId"),
            serverUrls = network.arrayOrNull("serverUrls").orEmpty().mapNotNull { it.primitiveContent() },
            modelPreferences = models.arrayOrNull("user").orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                DesktopModelPreference(
                    providerId = item.string("providerID") ?: return@mapNotNull null,
                    modelId = item.string("modelID") ?: return@mapNotNull null,
                    visibility = item.string("visibility") ?: return@mapNotNull null,
                )
            },
            recentModelKeys = models.arrayOrNull("recent").orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val providerId = item.string("providerID") ?: return@mapNotNull null
                val modelId = item.string("modelID") ?: return@mapNotNull null
                "$providerId/$modelId"
            },
        )
    }

    fun parseEvent(value: String): ServerEvent {
        val root = objectValue(value)
        val payload = root.objectOrNull("payload") ?: root
        return ServerEvent(
            id = payload.string("id"),
            type = payload.string("type").orEmpty(),
            properties = payload.objectOrNull("properties")
                ?: payload.objectOrNull("data")
                ?: JsonObject(emptyMap()),
            directory = root.string("directory")
                ?: root.objectOrNull("location")?.string("directory"),
        )
    }

    fun parseErrorMessage(value: String): String? {
        if (value.isBlank()) return null
        return runCatching {
            val root = objectValue(value)
            root.string("message")
                ?: root.objectOrNull("data")?.string("message")
                ?: root.objectOrNull("error")?.string("message")
        }.getOrNull()
    }

    fun objectValue(value: String): JsonObject = parser.parseToJsonElement(value).jsonObject

    fun arrayValue(value: String): JsonArray = parser.parseToJsonElement(value).jsonArray
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement.primitiveContent(): String? =
    (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement.compactString(): String = when (this) {
    JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}
