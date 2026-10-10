package ai.opencode.mobile.data

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenCodeApi(private val profile: ConnectionProfile) {
    private val baseUrl: HttpUrl = normalizeBaseUrl(profile.baseUrl).toHttpUrl()
    @Volatile private var bridgeBaseUrl: String = profile.bridgeBaseUrl
    @Volatile private var bridgeToken: String = profile.bridgeToken
    val configuredDirectory: String? = profile.directory.trim().takeIf { it.isNotEmpty() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val eventClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun health(): ServerHealth = OpenCodeJson.parseHealth(get("global/health", directory = null))

    suspend fun sessions(): List<OpenCodeSession> {
        val query = mapOf("roots" to "true", "limit" to "250")
        val response = try {
            get("experimental/session", query = query)
        } catch (error: OpenCodeApiException) {
            if (error.statusCode !in setOf(404, 405)) throw error
            get("session", query = mapOf("roots" to "true"))
        }
        return OpenCodeJson.parseSessions(response)
    }

    suspend fun statuses(directory: String? = configuredDirectory): Map<String, SessionStatus> =
        OpenCodeJson.parseStatuses(get("session/status", directory = directory))

    suspend fun activeSessions(): Set<String> {
        val root = OpenCodeJson.objectValue(get("api/session/active", directory = null))
        val data = root.objectOrNull("data") ?: root
        return data.keys
    }

    suspend fun messages(sessionId: String): List<SessionMessage> =
        OpenCodeJson.parseMessages(get("session/$sessionId/message", query = mapOf("limit" to "200")))

    suspend fun todos(sessionId: String): List<OpenCodeTodo> =
        OpenCodeJson.parseTodos(get("session/$sessionId/todo"))

    suspend fun diffs(sessionId: String): List<FileDiff> =
        OpenCodeJson.parseDiffs(get("session/$sessionId/diff"))

    suspend fun permissions(directory: String? = configuredDirectory): List<PermissionRequest> =
        OpenCodeJson.parsePermissions(get("permission", directory = directory), directory)

    suspend fun questions(directory: String? = configuredDirectory): List<QuestionRequest> =
        OpenCodeJson.parseQuestions(get("question", directory = directory), directory)

    suspend fun agents(directory: String? = configuredDirectory): List<AgentChoice> =
        OpenCodeJson.parseAgents(get("agent", directory = directory))

    suspend fun models(directory: String? = configuredDirectory): ModelCatalog {
        val providerResponse = try {
            get("provider", directory = directory)
        } catch (error: OpenCodeApiException) {
            if (error.statusCode !in setOf(404, 405)) throw error
            get("config/providers", directory = directory)
        }
        val configured = try {
            OpenCodeJson.objectValue(get("config", directory = directory)).string("model")
        } catch (error: OpenCodeApiException) {
            if (error.statusCode !in setOf(404, 405)) throw error
            null
        }
        return OpenCodeJson.parseModels(providerResponse, configured)
    }

    suspend fun desktopBridge(): DesktopBridgeState {
        val url = bridgeBaseUrl
        val token = bridgeToken
        if (url.isBlank() || token.isBlank()) {
            throw OpenCodeApiException(0, "Desktop bridge is not configured")
        }
        val bridgeUrl = normalizeBaseUrl(url).toHttpUrl().newBuilder()
            .encodedPath("/state")
            .query(null)
            .build()
        val request = Request.Builder()
            .url(bridgeUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return OpenCodeJson.parseDesktopBridge(execute(request))
    }

    fun configureDesktopBridge(url: String, token: String) {
        bridgeBaseUrl = normalizeBaseUrl(url)
        bridgeToken = token
    }

    suspend fun session(sessionId: String): OpenCodeSession =
        OpenCodeJson.parseSession(get("session/$sessionId"))

    suspend fun createSession(
        title: String,
        directory: String? = configuredDirectory,
    ): OpenCodeSession {
        val body = buildJsonObject {
            if (title.isNotBlank()) put("title", title.trim())
        }
        return OpenCodeJson.parseSession(post("session", body.toString(), directory))
    }

    suspend fun renameSession(sessionId: String, title: String): OpenCodeSession {
        val body = buildJsonObject { put("title", title.trim()) }
        return OpenCodeJson.parseSession(patch("session/$sessionId", body.toString()))
    }

    suspend fun deleteSession(sessionId: String) {
        delete("session/$sessionId")
    }

    suspend fun shareSession(sessionId: String): OpenCodeSession =
        OpenCodeJson.parseSession(post("session/$sessionId/share", EMPTY_JSON))

    suspend fun unshareSession(sessionId: String): OpenCodeSession =
        OpenCodeJson.parseSession(delete("session/$sessionId/share"))

    suspend fun promptAsync(
        sessionId: String,
        text: String,
        agent: String?,
        model: ModelChoice?,
    ) {
        val body = buildJsonObject {
            agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
            model?.let {
                putJsonObject("model") {
                    put("providerID", it.providerId)
                    put("modelID", it.modelId)
                }
            }
            putJsonArray("parts") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        }
        post("session/$sessionId/prompt_async", body.toString())
    }

    suspend fun abort(sessionId: String) {
        post("session/$sessionId/abort", body = null)
    }

    suspend fun revert(sessionId: String, messageId: String, partId: String? = null) {
        val body = buildJsonObject {
            put("messageID", messageId)
            partId?.let { put("partID", it) }
        }
        post("session/$sessionId/revert", body.toString())
    }

    suspend fun unrevert(sessionId: String) {
        post("session/$sessionId/unrevert", EMPTY_JSON)
    }

    suspend fun replyPermission(
        requestId: String,
        reply: String,
        message: String? = null,
        directory: String? = configuredDirectory,
    ) {
        val body = buildJsonObject {
            put("reply", reply)
            message?.takeIf { it.isNotBlank() }?.let { put("message", it) }
        }
        post("permission/$requestId/reply", body.toString(), directory)
    }

    suspend fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = configuredDirectory,
    ) {
        val body = buildJsonObject {
            put("answers", JsonArray(answers.map { answer ->
                buildJsonArray { answer.forEach { add(JsonPrimitive(it)) } }
            }))
        }
        post("question/$requestId/reply", body.toString(), directory)
    }

    suspend fun rejectQuestion(requestId: String, directory: String? = configuredDirectory) {
        post("question/$requestId/reject", body = null, directory = directory)
    }

    fun events(global: Boolean = true): Flow<ServerEvent> = callbackFlow {
        val request = requestBuilder(
            url(
                path = if (global) "global/event" else "event",
                directory = if (global) null else configuredDirectory,
            ),
        )
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val source = EventSources.createFactory(eventClient).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    runCatching { OpenCodeJson.parseEvent(data) }
                        .onSuccess { trySend(it) }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val cause = t ?: OpenCodeApiException(
                        statusCode = response?.code ?: 0,
                        message = response?.message ?: "Event stream closed",
                    )
                    close(cause)
                }
            },
        )
        awaitClose { source.cancel() }
    }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED)

    fun webUrl(): String = baseUrl.toString().trimEnd('/') + "/"

    private suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        directory: String? = configuredDirectory,
    ): String = execute(
        requestBuilder(url(path, query, directory)).get().build(),
    )

    private suspend fun post(
        path: String,
        body: String?,
        directory: String? = configuredDirectory,
    ): String {
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
            ?: ByteArray(0).toRequestBody(null, 0, 0)
        return execute(requestBuilder(url(path, directory = directory)).post(requestBody).build())
    }

    private suspend fun patch(path: String, body: String): String = execute(
        requestBuilder(url(path)).patch(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
    )

    private suspend fun delete(path: String): String = execute(
        requestBuilder(url(path)).delete().build(),
    )

    private fun requestBuilder(url: HttpUrl): Request.Builder {
        return Request.Builder().url(url).apply {
            if (profile.password.isNotEmpty()) {
                header(
                    "Authorization",
                    Credentials.basic(
                        profile.username.ifBlank { "opencode" },
                        profile.password,
                        StandardCharsets.UTF_8,
                    ),
                )
            }
        }
    }

    private fun url(
        path: String,
        query: Map<String, String> = emptyMap(),
        directory: String? = configuredDirectory,
    ): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments(path.trim('/'))
        directory?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("directory", it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!continuation.isActive) return
                    if (it.isSuccessful) {
                        continuation.resume(body)
                    } else {
                        val detail = OpenCodeJson.parseErrorMessage(body)
                        continuation.resumeWithException(
                            OpenCodeApiException(
                                statusCode = it.code,
                                message = detail ?: "OpenCode request failed (${it.code})",
                            ),
                        )
                    }
                }
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val EMPTY_JSON = "{}"

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Server URL is required" }
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val parsed = withScheme.toHttpUrl()
            require(parsed.scheme == "http" || parsed.scheme == "https") {
                "Only HTTP and HTTPS server URLs are supported"
            }
            return parsed.toString().trimEnd('/')
        }
    }
}

class OpenCodeApiException(
    val statusCode: Int,
    override val message: String,
) : IOException(message)
