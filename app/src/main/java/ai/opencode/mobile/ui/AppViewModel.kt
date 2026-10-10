package ai.opencode.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.opencode.mobile.data.AgentChoice
import ai.opencode.mobile.data.ConnectionProfile
import ai.opencode.mobile.data.ConnectionStore
import ai.opencode.mobile.data.DesktopBridgeState
import ai.opencode.mobile.data.FileDiff
import ai.opencode.mobile.data.MessagePart
import ai.opencode.mobile.data.ModelChoice
import ai.opencode.mobile.data.ModelCatalog
import ai.opencode.mobile.data.OpenCodeApi
import ai.opencode.mobile.data.OpenCodeApiException
import ai.opencode.mobile.data.OpenCodeJson
import ai.opencode.mobile.data.OpenCodeQuestion
import ai.opencode.mobile.data.OpenCodeSession
import ai.opencode.mobile.data.OpenCodeTodo
import ai.opencode.mobile.data.PermissionRequest
import ai.opencode.mobile.data.QuestionRequest
import ai.opencode.mobile.data.ServerEvent
import ai.opencode.mobile.data.ServerHealth
import ai.opencode.mobile.data.SessionDetail
import ai.opencode.mobile.data.SessionMessage
import ai.opencode.mobile.data.SessionStatus
import ai.opencode.mobile.data.arrayOrNull
import ai.opencode.mobile.data.int
import ai.opencode.mobile.data.long
import ai.opencode.mobile.data.objectOrNull
import ai.opencode.mobile.data.string
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class AppScreen { CONNECT, SESSIONS, SESSION, WEB }

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

enum class SessionPane { TIMELINE, CHANGES }

data class AppUiState(
    val screen: AppScreen = AppScreen.CONNECT,
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val profile: ConnectionProfile = ConnectionProfile(),
    val health: ServerHealth? = null,
    val sessions: List<OpenCodeSession> = emptyList(),
    val statuses: Map<String, SessionStatus> = emptyMap(),
    val activeSessionIds: Set<String> = emptySet(),
    val desktopBridge: DesktopBridgeState? = null,
    val permissions: List<PermissionRequest> = emptyList(),
    val questions: List<QuestionRequest> = emptyList(),
    val agents: List<AgentChoice> = emptyList(),
    val models: List<ModelChoice> = emptyList(),
    val allModels: List<ModelChoice> = emptyList(),
    val defaultModelKey: String? = null,
    val selectedAgent: String? = null,
    val selectedModel: ModelChoice? = null,
    val selectedSessionId: String? = null,
    val detail: SessionDetail = SessionDetail(),
    val pane: SessionPane = SessionPane.TIMELINE,
    val composerText: String = "",
    val searchQuery: String = "",
    val refreshing: Boolean = false,
    val streamConnected: Boolean = false,
    val error: String? = null,
    val actionInProgress: Boolean = false,
) {
    val selectedSession: OpenCodeSession?
        get() = sessions.firstOrNull { it.id == selectedSessionId }

    val selectedStatus: SessionStatus
        get() = statusFor(selectedSessionId)

    fun statusFor(sessionId: String?): SessionStatus {
        if (sessionId != null && sessionId in activeSessionIds) {
            return SessionStatus(SessionStatus.Type.BUSY)
        }
        return statuses[sessionId] ?: SessionStatus(SessionStatus.Type.IDLE)
    }

    val selectedPermission: PermissionRequest?
        get() = permissions.firstOrNull { it.sessionId == selectedSessionId }

    val selectedQuestion: QuestionRequest?
        get() = questions.firstOrNull { it.sessionId == selectedSessionId }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val _state = MutableStateFlow(
        AppUiState(profile = store.load() ?: ConnectionProfile()),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var api: OpenCodeApi? = null
    private var eventJob: Job? = null
    private var connectJob: Job? = null
    private var pendingEventJob: Job? = null
    private var detailJob: Job? = null
    private var eventDetailRefreshJob: Job? = null
    private var choicesJob: Job? = null
    private var refreshJob: Job? = null
    private var promptJob: Job? = null
    private var companionPollJob: Job? = null
    private val actionJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private var connectionGeneration = 0L
    private var connectionEpoch = 0L
    private val stateSync = Mutex()
    private val detailRefreshRequests = Channel<String>(Channel.CONFLATED)

    init {
        eventDetailRefreshJob = viewModelScope.launch {
            for (requestedSessionId in detailRefreshRequests) {
                delay(150)
                val sessionId = generateSequence(requestedSessionId) {
                    detailRefreshRequests.tryReceive().getOrNull()
                }.last()
                val client = api ?: continue
                val epoch = connectionEpoch
                if (_state.value.selectedSessionId != sessionId) continue
                runCatching {
                    stateSync.withLock {
                        ensureCurrent(client, epoch)
                        if (_state.value.selectedSessionId != sessionId) return@withLock
                        val detail = fetchDetail(client, sessionId)
                        ensureCurrent(client, epoch)
                        applyDetail(sessionId, detail)
                    }
                }
            }
        }
        store.load()?.let(::connect)
    }

    fun connect(profile: ConnectionProfile) {
        if (connectJob?.isActive == true) return
        val generation = ++connectionGeneration
        connectJob = viewModelScope.launch {
            try {
            val normalized = runCatching {
                profile.copy(
                    baseUrl = OpenCodeApi.normalizeBaseUrl(profile.baseUrl),
                    directory = profile.directory.trim(),
                    username = profile.username.trim().ifBlank { "opencode" },
                )
            }.getOrElse {
                setError(it.userMessage())
                return@launch
            }
            val previousApi = api
            val previousEventJob = eventJob
            val previousProfile = _state.value.profile
            val sameEndpoint = previousApi != null && sameServerIdentity(previousProfile, normalized)
            val preservedSessionId = _state.value.selectedSessionId.takeIf { sameEndpoint }
            _state.update {
                it.copy(
                    phase = ConnectionPhase.CONNECTING,
                    error = null,
                )
            }
            val candidate = OpenCodeApi(normalized)
            val connection = launchEventLoop(candidate, preservedSessionId)
            pendingEventJob = connection.job
            runCatching { withTimeout(60_000) { connection.ready.await() } }
                .onSuccess { bootstrap ->
                    if (generation != connectionGeneration) {
                        connection.job.cancelAndJoin()
                        if (pendingEventJob === connection.job) pendingEventJob = null
                        return@onSuccess
                    }
                    previousEventJob?.cancelAndJoin()
                    cancelConnectionScopedJobs()
                    connectionEpoch++
                    api = candidate
                    eventJob = connection.job
                    pendingEventJob = null
                    stateSync.withLock {
                        if (sameEndpoint) {
                            applySnapshot(bootstrap.snapshot)
                            bootstrap.detail?.let { detail ->
                                bootstrap.detailSessionId?.let { applyDetail(it, detail) }
                            }
                            _state.update { current ->
                                val selected = preservedSessionId?.takeIf { id ->
                                    bootstrap.snapshot.sessions.any { it.id == id }
                                }
                                current.copy(
                                    screen = if (selected != null) AppScreen.SESSION else AppScreen.SESSIONS,
                                    phase = ConnectionPhase.CONNECTED,
                                    profile = normalized,
                                    selectedSessionId = selected,
                                    streamConnected = true,
                                    error = null,
                                )
                            }
                        } else {
                            val snapshot = bootstrap.snapshot
                            val bridge = snapshot.desktopBridge
                            val visibleModels = visibleModels(snapshot.modelCatalog.models, bridge)
                            val selectedAgent = snapshot.agents
                                .firstOrNull { agent -> agent.name == "build" }
                            _state.update {
                                it.copy(
                                    screen = AppScreen.SESSIONS,
                                    phase = ConnectionPhase.CONNECTED,
                                    profile = normalized,
                                    health = snapshot.health,
                                    sessions = snapshot.sessions,
                                    statuses = snapshot.statuses,
                                    activeSessionIds = snapshot.activeSessionIds,
                                    desktopBridge = bridge,
                                    permissions = snapshot.permissions,
                                    questions = snapshot.questions,
                                    agents = snapshot.agents,
                                    allModels = snapshot.modelCatalog.models,
                                    models = visibleModels,
                                    defaultModelKey = snapshot.modelCatalog.defaultModelKey,
                                    selectedAgent = selectedAgent?.name,
                                    selectedModel = resolveDefaultModel(
                                        snapshot.modelCatalog,
                                        bridge,
                                        selectedAgent?.model,
                                    ),
                                    selectedSessionId = null,
                                    detail = SessionDetail(),
                                    pane = SessionPane.TIMELINE,
                                    composerText = "",
                                    streamConnected = true,
                                    error = null,
                                )
                            }
                        }
                        connection.activate.complete(Unit)
                    }
                    runCatching { store.save(normalized) }
                        .onFailure { setError("Connected, but the credentials could not be saved securely: ${it.userMessage()}") }
                    startCompanionPolling(candidate, connectionEpoch)
                }
                .onFailure { error ->
                    connection.job.cancelAndJoin()
                    if (pendingEventJob === connection.job) pendingEventJob = null
                    if (generation != connectionGeneration) return@onFailure
                    val canRestore = previousApi != null &&
                        api === previousApi &&
                        previousEventJob?.isActive == true
                    if (canRestore) {
                        api = previousApi
                        eventJob = previousEventJob
                    } else if (api === previousApi) {
                        api = null
                        eventJob = null
                    }
                    _state.update { current ->
                        current.copy(
                            phase = if (canRestore) ConnectionPhase.CONNECTED
                            else ConnectionPhase.DISCONNECTED,
                            screen = AppScreen.CONNECT,
                            profile = previousProfile,
                            streamConnected = canRestore && current.streamConnected,
                            error = error.userMessage(),
                        )
                    }
                }
            } finally {
                if (generation == connectionGeneration) connectJob = null
            }
        }
    }

    fun disconnect() {
        connectionGeneration++
        connectJob?.cancel()
        pendingEventJob?.cancel()
        pendingEventJob = null
        companionPollJob?.cancel()
        companionPollJob = null
        eventJob?.cancel()
        cancelConnectionScopedJobsNow()
        connectionEpoch++
        api = null
        store.clear()
        _state.update {
            AppUiState(
                screen = AppScreen.CONNECT,
                profile = it.profile.copy(password = "", bridgeToken = ""),
            )
        }
    }

    fun showConnections() {
        _state.update { it.copy(screen = AppScreen.CONNECT, error = null) }
    }

    fun showSessions() {
        _state.update { it.copy(screen = AppScreen.SESSIONS, error = null) }
    }

    fun showWeb() {
        _state.update { it.copy(screen = AppScreen.WEB, error = null) }
    }

    fun setSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }

    fun setPane(value: SessionPane) {
        _state.update { it.copy(pane = value) }
    }

    fun setComposerText(value: String) {
        _state.update { it.copy(composerText = value) }
    }

    fun selectAgent(value: String?) {
        _state.update { current ->
            val agentModel = current.agents.firstOrNull { it.name == value }?.model
            current.copy(
                selectedAgent = value,
                selectedModel = agentModel?.let { model ->
                    current.allModels.firstOrNull {
                        it.providerId == model.providerId && it.modelId == model.modelId
                    }
                } ?: current.selectedModel,
            )
        }
    }

    fun selectModel(value: ModelChoice?) {
        _state.update { it.copy(selectedModel = value) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun reportError(message: String) {
        setError(message)
    }

    fun configureDesktopBridge(url: String, token: String) {
        val normalized = runCatching { OpenCodeApi.normalizeBaseUrl(url) }.getOrNull() ?: return
        if (token.isBlank()) return
        val next = _state.value.profile.copy(bridgeBaseUrl = normalized, bridgeToken = token)
        _state.update { it.copy(profile = next) }
        runCatching { store.save(next) }
        api?.let { client ->
            client.configureDesktopBridge(normalized, token)
            startCompanionPolling(client, connectionEpoch)
        }
    }

    fun refresh() {
        val client = api ?: return
        val epoch = connectionEpoch
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            runCatching {
                stateSync.withLock {
                    ensureCurrent(client, epoch)
                    val snapshot = loadSnapshot(client)
                    ensureCurrent(client, epoch)
                    applySnapshot(snapshot)
                    _state.value.selectedSessionId?.let { sessionId ->
                        val detail = fetchDetail(client, sessionId)
                        ensureCurrent(client, epoch)
                        applyDetail(sessionId, detail)
                    }
                }
            }
                .onFailure {
                    if (it !is CancellationException && isCurrent(client, epoch)) setError(it.userMessage())
                }
            if (isCurrent(client, epoch)) _state.update { it.copy(refreshing = false) }
        }
    }

    fun openSession(sessionId: String) {
        _state.update {
            it.copy(
                screen = AppScreen.SESSION,
                selectedSessionId = sessionId,
                pane = SessionPane.TIMELINE,
                composerText = "",
                detail = SessionDetail(loading = true),
                error = null,
            )
        }
        val session = _state.value.sessions.firstOrNull { it.id == sessionId }
        session?.agent?.let(::selectAgent)
        session?.model?.let { model ->
            _state.value.models.firstOrNull {
                it.providerId == model.providerId && it.modelId == model.modelId
            }?.let(::selectModel)
        }
        session?.directory?.let(::loadChoices)
        loadDetail(sessionId)
    }

    fun createSession(title: String, directory: String) {
        if (directory.isBlank()) {
            setError("Choose a project directory before creating a session.")
            return
        }
        runAction {
            val session = it.client.createSession(title, directory.trim())
            it.ensureCurrent()
            _state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
            openSession(session.id)
        }
    }

    fun renameSession(sessionId: String, title: String) {
        if (title.isBlank()) return
        runAction { action ->
            val session = action.client.renameSession(sessionId, title)
            action.ensureCurrent()
            _state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
        }
    }

    fun deleteSession(sessionId: String) {
        runAction { action ->
            action.client.deleteSession(sessionId)
            action.ensureCurrent()
            _state.update {
                it.copy(
                    screen = AppScreen.SESSIONS,
                    sessions = it.sessions.filterNot { session -> session.id == sessionId },
                    statuses = it.statuses - sessionId,
                    selectedSessionId = null,
                    detail = SessionDetail(),
                )
            }
        }
    }

    fun toggleShare(sessionId: String) {
        runAction { action ->
            val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return@runAction
            val updated = if (session.shareUrl == null) {
                action.client.shareSession(sessionId)
            } else {
                action.client.unshareSession(sessionId)
            }
            action.ensureCurrent()
            _state.update { it.copy(sessions = upsertSession(it.sessions, updated)) }
        }
    }

    fun sendPrompt() {
        val current = _state.value
        val sessionId = current.selectedSessionId ?: return
        val text = current.composerText.trim()
        if (text.isEmpty()) return
        val client = api ?: return
        val epoch = connectionEpoch
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            _state.update { it.copy(actionInProgress = true, error = null) }
            runCatching {
                client.promptAsync(
                    sessionId = sessionId,
                    text = text,
                    agent = current.selectedAgent,
                    model = current.selectedModel,
                )
            }.onSuccess {
                ensureCurrent(client, epoch)
                _state.update { it.copy(composerText = "") }
            }.onFailure {
                if (it !is CancellationException && isCurrent(client, epoch)) setError(it.userMessage())
            }
            if (isCurrent(client, epoch)) _state.update { it.copy(actionInProgress = false) }
        }
    }

    fun abort() {
        val sessionId = _state.value.selectedSessionId ?: return
        runAction { it.client.abort(sessionId) }
    }

    fun revert(messageId: String, partId: String? = null) {
        val sessionId = _state.value.selectedSessionId ?: return
        runAction { action ->
            action.client.revert(sessionId, messageId, partId)
            action.ensureCurrent()
            stateSync.withLock {
                val detail = fetchDetail(action.client, sessionId)
                action.ensureCurrent()
                applyDetail(sessionId, detail)
            }
        }
    }

    fun replyPermission(requestId: String, reply: String) {
        runAction { action ->
            val request = _state.value.permissions.firstOrNull { it.id == requestId }
            action.client.replyPermission(requestId, reply, directory = request?.directory)
            action.ensureCurrent()
            _state.update {
                it.copy(permissions = it.permissions.filterNot { request -> request.id == requestId })
            }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>) {
        runAction { action ->
            val request = _state.value.questions.firstOrNull { it.id == requestId }
            action.client.replyQuestion(requestId, answers, directory = request?.directory)
            action.ensureCurrent()
            _state.update {
                it.copy(questions = it.questions.filterNot { request -> request.id == requestId })
            }
        }
    }

    fun rejectQuestion(requestId: String) {
        runAction { action ->
            val request = _state.value.questions.firstOrNull { it.id == requestId }
            action.client.rejectQuestion(requestId, directory = request?.directory)
            action.ensureCurrent()
            _state.update {
                it.copy(questions = it.questions.filterNot { request -> request.id == requestId })
            }
        }
    }

    fun webUrl(): String = api?.webUrl() ?: _state.value.profile.baseUrl

    private fun loadDetail(sessionId: String, quiet: Boolean = false) {
        val client = api ?: return
        val epoch = connectionEpoch
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(detail = it.detail.copy(loading = true)) }
            runCatching {
                stateSync.withLock {
                    ensureCurrent(client, epoch)
                    val detail = fetchDetail(client, sessionId)
                    ensureCurrent(client, epoch)
                    applyDetail(sessionId, detail)
                }
            }
                .onFailure {
                    if (it !is CancellationException && isCurrent(client, epoch)) setError(it.userMessage())
                }
        }
    }

    private suspend fun fetchDetail(client: OpenCodeApi, sessionId: String): SessionDetail = coroutineScope {
        val previous = _state.value.detail.takeIf { _state.value.selectedSessionId == sessionId }
        val messages = async { client.messages(sessionId) }
        val todos = async { runCatching { client.todos(sessionId) }.getOrDefault(previous?.todos.orEmpty()) }
        val diffs = async { runCatching { client.diffs(sessionId) }.getOrDefault(previous?.diffs.orEmpty()) }
        SessionDetail(
            loading = false,
            messages = messages.await(),
            todos = todos.await(),
            diffs = diffs.await(),
        )
    }

    private fun applyDetail(sessionId: String, detail: SessionDetail) {
        if (_state.value.selectedSessionId == sessionId) {
            _state.update { it.copy(detail = detail) }
        }
    }

    private fun launchEventLoop(
        client: OpenCodeApi,
        bootstrapSessionId: String? = null,
    ): PendingConnection {
        val ready = CompletableDeferred<Bootstrap>()
        val activate = CompletableDeferred<Unit>()
        val job = viewModelScope.launch {
            var retryDelay = 1_000L
            var useGlobalEvents = true
            var initialCycle = true
            while (isActive) {
                val cycleLock = Mutex()
                val pending = mutableListOf<ServerEvent>()
                val connected = CompletableDeferred<Unit>()
                val ended = CompletableDeferred<Unit>()
                var failure: Throwable? = null
                var active = false
                val stream = launch {
                    try {
                        client.events(global = useGlobalEvents).collect { event ->
                            if (event.type == "server.connected" && !connected.isCompleted) {
                                connected.complete(Unit)
                            }
                            cycleLock.withLock {
                                if (!active) {
                                    pending += event
                                } else {
                                    stateSync.withLock {
                                        if (api === client) applyEvent(event)
                                    }
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        if (error !is CancellationException) failure = error
                    } finally {
                        if (!ended.isCompleted) ended.complete(Unit)
                    }
                }
                val reachedServer = select {
                    connected.onAwait { true }
                    ended.onAwait { false }
                }
                val apiFailure = failure as? OpenCodeApiException
                if (!reachedServer) {
                    stream.cancelAndJoin()
                    if (useGlobalEvents && apiFailure?.statusCode in setOf(404, 405)) {
                        useGlobalEvents = false
                        continue
                    }
                    if (initialCycle) {
                        ready.completeExceptionally(failure ?: IOException("OpenCode event stream closed"))
                        return@launch
                    }
                    if (handlePermanentStreamFailure(client, apiFailure)) return@launch
                    markReconnecting(client, failure)
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(15_000L)
                    continue
                }

                val snapshotResult = runCatching { loadSnapshot(client) }
                if (snapshotResult.isFailure) {
                    cycleLock.withLock {
                        stateSync.withLock {
                            if (api === client) pending.forEach(::applyEvent)
                        }
                    }
                    stream.cancelAndJoin()
                    val error = snapshotResult.exceptionOrNull()
                    if (initialCycle) {
                        ready.completeExceptionally(error ?: IOException("Failed to load OpenCode state"))
                        return@launch
                    }
                    if (handlePermanentStreamFailure(client, error as? OpenCodeApiException)) return@launch
                    markReconnecting(client, error)
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(15_000L)
                    continue
                }

                val snapshot = snapshotResult.getOrThrow()
                val detailSessionId = if (initialCycle) {
                    bootstrapSessionId
                } else {
                    _state.value.selectedSessionId.takeIf { api === client }
                }?.takeIf { sessionId -> snapshot.sessions.any { it.id == sessionId } }
                val detail = detailSessionId?.let { sessionId ->
                    runCatching { fetchDetail(client, sessionId) }.getOrNull()
                }
                if (initialCycle) {
                    ready.complete(Bootstrap(snapshot, detailSessionId, detail))
                    activate.await()
                    if (!isActive) return@launch
                }

                cycleLock.withLock {
                    stateSync.withLock {
                        if (api === client) {
                            if (!initialCycle) {
                                applySnapshot(snapshot)
                                if (detailSessionId != null && detail != null) {
                                    applyDetail(detailSessionId, detail)
                                }
                            }
                            pending.forEach(::applyEvent)
                            active = true
                            detailSessionId?.let(::scheduleDetailRefresh)
                            if (connectJob?.isActive != true) {
                                _state.update {
                                    it.copy(
                                        phase = ConnectionPhase.CONNECTED,
                                        streamConnected = true,
                                    )
                                }
                            }
                        }
                    }
                }
                initialCycle = false
                retryDelay = 1_000L
                stream.join()
                if (!isActive || api !== client) return@launch

                val endedFailure = failure as? OpenCodeApiException
                if (
                    useGlobalEvents &&
                    endedFailure?.statusCode in setOf(404, 405)
                ) {
                    useGlobalEvents = false
                    continue
                }
                if (handlePermanentStreamFailure(client, endedFailure)) return@launch
                markReconnecting(client, failure)
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(15_000L)
            }
        }
        return PendingConnection(job, ready, activate)
    }

    private suspend fun handlePermanentStreamFailure(
        client: OpenCodeApi,
        failure: OpenCodeApiException?,
    ): Boolean {
        if (failure?.statusCode !in setOf(401, 403)) return false
        var disconnected = false
        stateSync.withLock {
            if (api === client) {
                api = null
                eventJob = null
                connectionEpoch++
                disconnected = true
                if (connectJob?.isActive != true) {
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.DISCONNECTED,
                            screen = AppScreen.CONNECT,
                            streamConnected = false,
                            error = failure?.userMessage(),
                        )
                    }
                }
            }
        }
        if (disconnected) cancelConnectionScopedJobs()
        return true
    }

    private suspend fun markReconnecting(client: OpenCodeApi, failure: Throwable?) {
        stateSync.withLock {
            if (api !== client) return@withLock
            if (connectJob?.isActive == true) return@withLock
            _state.update {
                it.copy(
                    phase = ConnectionPhase.RECONNECTING,
                    streamConnected = false,
                    error = when (failure) {
                        is OpenCodeApiException -> failure.userMessage()
                        is IOException, null -> null
                        else -> failure.userMessage()
                    },
                )
            }
        }
    }

    private fun applyEvent(event: ServerEvent) {
        if (!eventMatchesConfiguredDirectory(event)) return
        val properties = event.properties
        when (event.type) {
            "server.connected", "server.heartbeat" -> Unit
            "session.created", "session.updated" -> {
                val session = properties.objectOrNull("info")?.let(OpenCodeJson::parseSession) ?: return
                _state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
            }
            "session.deleted" -> {
                val sessionId = properties.string("sessionID")
                    ?: properties.objectOrNull("info")?.string("id")
                    ?: return
                _state.update { current ->
                    current.copy(
                        sessions = current.sessions.filterNot { it.id == sessionId },
                        statuses = current.statuses - sessionId,
                        activeSessionIds = current.activeSessionIds - sessionId,
                        screen = if (current.selectedSessionId == sessionId) AppScreen.SESSIONS else current.screen,
                        selectedSessionId = current.selectedSessionId.takeUnless { it == sessionId },
                    )
                }
            }
            "session.status" -> {
                val sessionId = properties.string("sessionID") ?: return
                val status = properties.objectOrNull("status")?.let(OpenCodeJson::parseStatus) ?: return
                _state.update {
                    it.copy(
                        statuses = it.statuses + (sessionId to status),
                        activeSessionIds = if (status.type == SessionStatus.Type.BUSY) {
                            it.activeSessionIds + sessionId
                        } else {
                            it.activeSessionIds - sessionId
                        },
                    )
                }
            }
            "session.idle" -> {
                val sessionId = properties.string("sessionID") ?: return
                _state.update {
                    it.copy(
                        statuses = it.statuses + (sessionId to SessionStatus(SessionStatus.Type.IDLE)),
                        activeSessionIds = it.activeSessionIds - sessionId,
                    )
                }
            }
            "session.diff" -> applyDiffEvent(properties)
            "message.updated" -> applyMessageEvent(properties)
            "message.removed" -> applyMessageRemoved(properties)
            "message.part.updated" -> applyPartEvent(properties)
            "message.part.removed" -> applyPartRemoved(properties)
            "message.part.delta" -> applyPartDelta(properties)
            "todo.updated" -> applyTodoEvent(properties)
            "permission.asked", "permission.v2.asked" -> {
                val request = OpenCodeJson.parsePermission(properties, event.directory) ?: return
                _state.update { it.copy(permissions = upsertPermission(it.permissions, request)) }
            }
            "permission.replied", "permission.v2.replied" -> {
                val requestId = properties.string("requestID") ?: return
                _state.update {
                    it.copy(permissions = it.permissions.filterNot { request -> request.id == requestId })
                }
            }
            "question.asked", "question.v2.asked" -> {
                val request = OpenCodeJson.parseQuestionRequest(properties, event.directory) ?: return
                _state.update { it.copy(questions = upsertQuestion(it.questions, request)) }
            }
            "question.replied", "question.rejected",
            "question.v2.replied", "question.v2.rejected" -> {
                val requestId = properties.string("requestID") ?: return
                _state.update {
                    it.copy(questions = it.questions.filterNot { request -> request.id == requestId })
                }
            }
            "session.error" -> {
                val error = properties.objectOrNull("error")
                val message = error?.objectOrNull("data")?.string("message")
                    ?: error?.string("message")
                    ?: error?.string("name")
                if (!message.isNullOrBlank()) setError(message)
            }
            else -> applyCurrentProtocolEvent(event)
        }
    }

    private fun applyCurrentProtocolEvent(event: ServerEvent) {
        if (!event.type.startsWith("session.")) return
        val sessionId = event.properties.string("sessionID") ?: return
        when (event.type) {
            "session.next.step.started" -> {
                ensureCurrentAssistant(event.properties)
                setSessionBusy(sessionId)
            }
            "session.next.text.started" -> {
                upsertCurrentTextPart(event.properties, "text", replace = "")
                setSessionBusy(sessionId)
            }
            "session.next.text.delta" -> {
                upsertCurrentTextPart(
                    event.properties,
                    type = "text",
                    append = event.properties.string("delta").orEmpty(),
                )
                setSessionBusy(sessionId)
            }
            "session.next.text.ended" -> {
                upsertCurrentTextPart(
                    event.properties,
                    type = "text",
                    replace = event.properties.string("text").orEmpty(),
                )
            }
            "session.next.reasoning.started" -> {
                upsertCurrentTextPart(event.properties, "reasoning", replace = "")
                setSessionBusy(sessionId)
            }
            "session.next.reasoning.delta" -> {
                upsertCurrentTextPart(
                    event.properties,
                    type = "reasoning",
                    append = event.properties.string("delta").orEmpty(),
                )
                setSessionBusy(sessionId)
            }
            "session.next.reasoning.ended" -> {
                upsertCurrentTextPart(
                    event.properties,
                    type = "reasoning",
                    replace = event.properties.string("text").orEmpty(),
                )
            }
            "session.next.tool.input.started",
            "session.next.tool.input.delta",
            "session.next.tool.input.ended",
            "session.next.tool.called",
            "session.next.tool.progress" -> setSessionBusy(sessionId)
            "session.next.tool.success",
            "session.next.tool.failed" -> Unit
            "session.next.retried" -> {
                val error = event.properties.objectOrNull("error")
                _state.update {
                    it.copy(
                        statuses = it.statuses + (
                            sessionId to SessionStatus(
                                type = SessionStatus.Type.RETRY,
                                attempt = event.properties.int("attempt"),
                                message = error?.string("message") ?: error?.toString(),
                                nextAt = event.properties.long("timestamp"),
                            )
                        ),
                    )
                }
            }
            "session.next.step.ended",
            "session.next.step.failed" -> {
                _state.update {
                    it.copy(
                        statuses = it.statuses + (sessionId to SessionStatus(SessionStatus.Type.IDLE)),
                        activeSessionIds = it.activeSessionIds - sessionId,
                    )
                }
            }
            "session.step.started",
            "session.text.started",
            "session.text.delta",
            "session.reasoning.started",
            "session.reasoning.delta",
            "session.tool.input.started",
            "session.tool.input.delta",
            "session.tool.called",
            "session.tool.progress" -> {
                _state.update {
                    it.copy(statuses = it.statuses + (sessionId to SessionStatus(SessionStatus.Type.BUSY)))
                }
            }
            "session.retry.scheduled" -> {
                _state.update {
                    it.copy(
                        statuses = it.statuses + (
                            sessionId to SessionStatus(
                                type = SessionStatus.Type.RETRY,
                                attempt = event.properties.int("attempt"),
                                message = event.properties.string("error"),
                                nextAt = event.properties.long("at"),
                            )
                        ),
                    )
                }
            }
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted" -> {
                _state.update {
                    it.copy(
                        statuses = it.statuses + (sessionId to SessionStatus(SessionStatus.Type.IDLE)),
                        activeSessionIds = it.activeSessionIds - sessionId,
                    )
                }
            }
        }
        if (
            _state.value.selectedSessionId == sessionId &&
            event.type !in setOf(
                "session.next.text.delta",
                "session.next.reasoning.delta",
            )
        ) {
            scheduleDetailRefresh(sessionId)
        }
    }

    private fun scheduleDetailRefresh(sessionId: String) {
        detailRefreshRequests.trySend(sessionId)
    }

    private fun setSessionBusy(sessionId: String) {
        _state.update {
            it.copy(
                statuses = it.statuses + (sessionId to SessionStatus(SessionStatus.Type.BUSY)),
                activeSessionIds = it.activeSessionIds + sessionId,
            )
        }
    }

    private fun ensureCurrentAssistant(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        val messageId = properties.string("assistantMessageID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        _state.update { current ->
            if (current.detail.messages.any { it.info.id == messageId }) return@update current
            val model = properties.objectOrNull("model")
            val info = ai.opencode.mobile.data.MessageInfo(
                id = messageId,
                sessionId = sessionId,
                role = ai.opencode.mobile.data.MessageInfo.Role.ASSISTANT,
                createdAt = properties.long("timestamp") ?: System.currentTimeMillis(),
                agent = properties.string("agent"),
                providerId = model?.string("providerID"),
                modelId = model?.string("modelID") ?: model?.string("id"),
            )
            current.copy(
                detail = current.detail.copy(
                    messages = (current.detail.messages + SessionMessage(info, emptyList()))
                        .sortedBy { it.info.createdAt },
                ),
            )
        }
    }

    private fun upsertCurrentTextPart(
        properties: JsonObject,
        type: String,
        append: String? = null,
        replace: String? = null,
    ) {
        val sessionId = properties.string("sessionID") ?: return
        val messageId = properties.string("assistantMessageID") ?: return
        val partId = when (type) {
            "reasoning" -> properties.string("reasoningID")
            else -> properties.string("textID")
        } ?: return
        if (_state.value.selectedSessionId != sessionId) return
        ensureCurrentAssistant(properties)
        _state.update { current ->
            current.copy(
                detail = current.detail.copy(
                    messages = current.detail.messages.map { message ->
                        if (message.info.id != messageId) return@map message
                        val parts = message.parts.toMutableList()
                        val index = parts.indexOfFirst { it.id == partId }
                        val existing = parts.getOrNull(index)
                        val text = replace ?: (existing?.text.orEmpty() + append.orEmpty())
                        val next = MessagePart(
                            id = partId,
                            sessionId = sessionId,
                            messageId = messageId,
                            type = type,
                            text = text,
                            startedAt = properties.long("timestamp"),
                            raw = properties,
                        )
                        if (index >= 0) parts[index] = next else parts += next
                        message.copy(parts = parts)
                    },
                ),
            )
        }
    }

    private fun eventMatchesConfiguredDirectory(event: ServerEvent): Boolean {
        val configured = api?.configuredDirectory ?: return true
        val eventDirectory = event.directory ?: return true
        if (eventDirectory == "global") return true
        return normalizePath(configured) == normalizePath(eventDirectory)
    }

    private fun loadChoices(directory: String) {
        val client = api ?: return
        val epoch = connectionEpoch
        choicesJob?.cancel()
        choicesJob = viewModelScope.launch {
            val session = _state.value.selectedSession
            val sessionId = session?.id
            val choices = runCatching {
                coroutineScope {
                    val agents = async { client.agents(directory) }
                    val models = async { client.models(directory) }
                    agents.await() to models.await()
                }
            }.getOrNull() ?: return@launch
            if (!isCurrent(client, epoch)) return@launch
            if (_state.value.selectedSessionId != sessionId) return@launch
            _state.update { current ->
                val catalog = choices.second
                val visibleModels = visibleModels(catalog.models, current.desktopBridge)
                val selectedAgent = session?.agent ?: current.selectedAgent
                val agentModel = choices.first.firstOrNull { it.name == selectedAgent }?.model
                val selectedModel = session?.model?.let { model ->
                    catalog.models.firstOrNull {
                        it.providerId == model.providerId && it.modelId == model.modelId
                    }
                } ?: current.selectedModel?.takeIf { selected ->
                    catalog.models.any { it.key == selected.key }
                } ?: resolveDefaultModel(catalog, current.desktopBridge, agentModel)
                current.copy(
                    agents = choices.first,
                    allModels = catalog.models,
                    models = visibleModels,
                    defaultModelKey = catalog.defaultModelKey,
                    selectedAgent = selectedAgent,
                    selectedModel = selectedModel,
                )
            }
        }
    }

    private fun startCompanionPolling(client: OpenCodeApi, epoch: Long) {
        companionPollJob?.cancel()
        companionPollJob = viewModelScope.launch {
            while (isActive && isCurrent(client, epoch)) {
                val active = runCatching {
                    val rootIds = _state.value.sessions.mapTo(mutableSetOf()) { it.id }
                    client.activeSessions().mapTo(mutableSetOf()) { activeId ->
                        resolveRootSession(client, activeId, rootIds)
                    }
                }.getOrNull()
                val bridge = runCatching { client.desktopBridge() }.getOrNull()
                if (active != null || bridge != null) {
                    stateSync.withLock {
                        if (!isCurrent(client, epoch)) return@withLock
                        _state.update { current ->
                            val nextBridge = bridge ?: current.desktopBridge
                            current.copy(
                                activeSessionIds = active ?: current.activeSessionIds,
                                desktopBridge = nextBridge,
                                models = visibleModels(current.allModels, nextBridge),
                            )
                        }
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun applyMessageEvent(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        val info = properties.objectOrNull("info")?.let(OpenCodeJson::parseMessageInfo) ?: return
        _state.update { current ->
            val messages = current.detail.messages.toMutableList()
            val index = messages.indexOfFirst { it.info.id == info.id }
            if (index >= 0) messages[index] = messages[index].copy(info = info)
            else messages += SessionMessage(info, emptyList())
            current.copy(detail = current.detail.copy(messages = messages.sortedBy { it.info.createdAt }))
        }
    }

    private fun applyMessageRemoved(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        val messageId = properties.string("messageID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        _state.update {
            it.copy(detail = it.detail.copy(messages = it.detail.messages.filterNot { message ->
                message.info.id == messageId
            }))
        }
    }

    private fun applyPartEvent(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        val part = properties.objectOrNull("part")?.let(OpenCodeJson::parsePart) ?: return
        upsertPart(part)
    }

    private fun applyPartRemoved(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        val messageId = properties.string("messageID") ?: return
        val partId = properties.string("partID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        _state.update { current ->
            current.copy(
                detail = current.detail.copy(
                    messages = current.detail.messages.map { message ->
                        if (message.info.id == messageId) {
                            message.copy(parts = message.parts.filterNot { it.id == partId })
                        } else message
                    },
                ),
            )
        }
    }

    private fun applyPartDelta(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        val messageId = properties.string("messageID") ?: return
        val partId = properties.string("partID") ?: return
        val field = properties.string("field") ?: return
        val delta = properties.string("delta") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        _state.update { current ->
            current.copy(
                detail = current.detail.copy(
                    messages = current.detail.messages.map { message ->
                        if (message.info.id != messageId) return@map message
                        message.copy(parts = message.parts.map { part ->
                            if (part.id != partId) return@map part
                            when (field) {
                                "text" -> part.copy(text = part.text.orEmpty() + delta)
                                "output" -> part.copy(toolOutput = part.toolOutput.orEmpty() + delta)
                                else -> part
                            }
                        })
                    },
                ),
            )
        }
    }

    private fun upsertPart(part: MessagePart) {
        _state.update { current ->
            val messages = current.detail.messages.map { message ->
                if (message.info.id != part.messageId) return@map message
                val parts = message.parts.toMutableList()
                val index = parts.indexOfFirst { it.id == part.id }
                if (index >= 0) parts[index] = part else parts += part
                message.copy(parts = parts)
            }
            current.copy(detail = current.detail.copy(messages = messages))
        }
    }

    private fun applyTodoEvent(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        val todos = properties.arrayOrNull("todos")?.let(OpenCodeJson::parseTodos) ?: return
        _state.update { it.copy(detail = it.detail.copy(todos = todos)) }
    }

    private fun applyDiffEvent(properties: JsonObject) {
        val sessionId = properties.string("sessionID") ?: return
        if (_state.value.selectedSessionId != sessionId) return
        val diffs = properties.arrayOrNull("diff")?.let(OpenCodeJson::parseDiffs) ?: return
        _state.update { it.copy(detail = it.detail.copy(diffs = diffs)) }
    }

    private fun runAction(block: suspend (ActionContext) -> Unit) {
        val client = api ?: return
        val epoch = connectionEpoch
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _state.update { it.copy(actionInProgress = true, error = null) }
            runCatching { block(ActionContext(client, epoch)) }.onFailure {
                if (it !is CancellationException && isCurrent(client, epoch)) setError(it.userMessage())
            }
            actionJobs.remove(job)
            val actionsIdle = synchronized(actionJobs) { actionJobs.none { it.isActive } }
            if (isCurrent(client, epoch) && actionsIdle) {
                _state.update { it.copy(actionInProgress = false) }
            }
        }
        actionJobs += job
        job.start()
    }

    private fun isCurrent(client: OpenCodeApi, epoch: Long): Boolean =
        api === client && connectionEpoch == epoch

    private fun ensureCurrent(client: OpenCodeApi, epoch: Long) {
        if (!isCurrent(client, epoch)) throw CancellationException("OpenCode connection changed")
    }

    private suspend fun cancelConnectionScopedJobs() {
        val jobs = synchronized(actionJobs) {
            buildList {
                detailJob?.let(::add)
                choicesJob?.let(::add)
                refreshJob?.let(::add)
                promptJob?.let(::add)
                addAll(actionJobs)
            }.distinct()
        }
        detailJob = null
        choicesJob = null
        refreshJob = null
        promptJob = null
        jobs.forEach(Job::cancel)
        jobs.joinAll()
        actionJobs.clear()
        _state.update { it.copy(actionInProgress = false, refreshing = false) }
    }

    private fun cancelConnectionScopedJobsNow() {
        detailJob?.cancel()
        choicesJob?.cancel()
        refreshJob?.cancel()
        promptJob?.cancel()
        synchronized(actionJobs) { actionJobs.forEach(Job::cancel) }
        detailJob = null
        choicesJob = null
        refreshJob = null
        promptJob = null
        actionJobs.clear()
    }

    private suspend fun loadSnapshot(client: OpenCodeApi): Snapshot = coroutineScope {
        val health = async { client.health() }
        val active = async { runCatching { client.activeSessions() }.getOrDefault(emptySet()) }
        val bridge = async { runCatching { client.desktopBridge() }.getOrNull() }
        val sessions = client.sessions().sortedByDescending { it.updatedAt }
        val directories: List<String?> = client.configuredDirectory?.let(::listOf)
            ?: sessions.map { it.directory }
                .filter { it.isNotBlank() }
                .distinctBy(::normalizePath)
                .ifEmpty { listOf(null) }
        val gate = Semaphore(6)
        val projectState = directories.map { directory ->
            async {
                gate.withPermit {
                    ProjectStateSnapshot(
                        directory = directory,
                        statuses = snapshotValue(emptyMap()) { client.statuses(directory) },
                        permissions = snapshotValue(emptyList()) { client.permissions(directory) },
                        questions = snapshotValue(emptyList()) { client.questions(directory) },
                    )
                }
            }
        }.awaitAll()
        val choiceDirectory = client.configuredDirectory ?: sessions.firstOrNull()?.directory
        val agents = async { runCatching { client.agents(choiceDirectory) }.getOrDefault(emptyList()) }
        val models = async { runCatching { client.models(choiceDirectory) }.getOrDefault(ModelCatalog(emptyList())) }
        val statusMap: Map<String, SessionStatus> = projectState
            .mapNotNull { it.statuses }
            .fold(emptyMap<String, SessionStatus>()) { all, item -> all + item }
        val rawActive = active.await() + statusMap.filterValues { it.type == SessionStatus.Type.BUSY }.keys
        val rootIds = sessions.mapTo(mutableSetOf()) { it.id }
        val activeSessions = rawActive.mapTo(mutableSetOf()) { activeId ->
            resolveRootSession(client, activeId, rootIds)
        }
        Snapshot(
            health = health.await(),
            sessions = sessions,
            statuses = statusMap,
            activeSessionIds = activeSessions,
            desktopBridge = bridge.await(),
            statusDirectories = projectState.filter { it.statuses != null }.mapTo(mutableSetOf()) {
                directoryKey(it.directory)
            },
            permissions = projectState.flatMap { it.permissions.orEmpty() }.distinctBy { it.id },
            permissionDirectories = projectState.filter { it.permissions != null }.mapTo(mutableSetOf()) {
                directoryKey(it.directory)
            },
            questions = projectState.flatMap { it.questions.orEmpty() }.distinctBy { it.id },
            questionDirectories = projectState.filter { it.questions != null }.mapTo(mutableSetOf()) {
                directoryKey(it.directory)
            },
            agents = agents.await(),
            modelCatalog = models.await(),
        )
    }

    private suspend fun resolveRootSession(
        client: OpenCodeApi,
        sessionId: String,
        rootIds: Set<String>,
    ): String {
        var currentId = sessionId
        val visited = mutableSetOf<String>()
        repeat(16) {
            if (currentId in rootIds || !visited.add(currentId)) return currentId
            val parent = runCatching { client.session(currentId).parentId }.getOrNull() ?: return currentId
            currentId = parent
        }
        return currentId
    }

    private fun applySnapshot(snapshot: Snapshot) {
        _state.update { current ->
            val bridge = snapshot.desktopBridge ?: current.desktopBridge
            val visibleModels = visibleModels(snapshot.modelCatalog.models, bridge)
            val agentModel = snapshot.agents.firstOrNull { it.name == current.selectedAgent }?.model
            val sessionDirectories = snapshot.sessions.associate { it.id to directoryKey(it.directory) }
            val retainedStatuses = current.statuses.filter { (sessionId, _) ->
                val directory = sessionDirectories[sessionId] ?: return@filter false
                directory !in snapshot.statusDirectories
            }
            val retainedPermissions = current.permissions.filter { request ->
                directoryKey(request.directory ?: current.sessions.firstOrNull { it.id == request.sessionId }?.directory) !in
                    snapshot.permissionDirectories
            }
            val retainedQuestions = current.questions.filter { request ->
                directoryKey(request.directory ?: current.sessions.firstOrNull { it.id == request.sessionId }?.directory) !in
                    snapshot.questionDirectories
            }
            current.copy(
                health = snapshot.health,
                sessions = snapshot.sessions,
                statuses = retainedStatuses + snapshot.statuses,
                activeSessionIds = snapshot.activeSessionIds,
                desktopBridge = bridge,
                permissions = (retainedPermissions + snapshot.permissions).distinctBy { it.id },
                questions = (retainedQuestions + snapshot.questions).distinctBy { it.id },
                agents = snapshot.agents,
                allModels = snapshot.modelCatalog.models,
                models = visibleModels,
                defaultModelKey = snapshot.modelCatalog.defaultModelKey,
                selectedModel = current.selectedModel?.takeIf { selected ->
                    snapshot.modelCatalog.models.any { it.key == selected.key }
                } ?: resolveDefaultModel(snapshot.modelCatalog, bridge, agentModel),
                phase = ConnectionPhase.CONNECTED,
            )
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(error = message) }
    }

    private data class Snapshot(
        val health: ServerHealth,
        val sessions: List<OpenCodeSession>,
        val statuses: Map<String, SessionStatus>,
        val activeSessionIds: Set<String>,
        val desktopBridge: DesktopBridgeState?,
        val statusDirectories: Set<String>,
        val permissions: List<PermissionRequest>,
        val permissionDirectories: Set<String>,
        val questions: List<QuestionRequest>,
        val questionDirectories: Set<String>,
        val agents: List<AgentChoice>,
        val modelCatalog: ModelCatalog,
    )

    private data class ProjectStateSnapshot(
        val directory: String?,
        val statuses: Map<String, SessionStatus>?,
        val permissions: List<PermissionRequest>?,
        val questions: List<QuestionRequest>?,
    )

    private data class PendingConnection(
        val job: Job,
        val ready: CompletableDeferred<Bootstrap>,
        val activate: CompletableDeferred<Unit>,
    )

    private data class Bootstrap(
        val snapshot: Snapshot,
        val detailSessionId: String?,
        val detail: SessionDetail?,
    )

    private inner class ActionContext(
        val client: OpenCodeApi,
        private val epoch: Long,
    ) {
        fun ensureCurrent() = this@AppViewModel.ensureCurrent(client, epoch)
    }
}

private fun upsertSession(
    sessions: List<OpenCodeSession>,
    session: OpenCodeSession,
): List<OpenCodeSession> {
    return (sessions.filterNot { it.id == session.id } + session)
        .sortedByDescending { it.updatedAt }
}

private fun upsertPermission(
    requests: List<PermissionRequest>,
    request: PermissionRequest,
): List<PermissionRequest> = requests.filterNot { it.id == request.id } + request

private fun upsertQuestion(
    requests: List<QuestionRequest>,
    request: QuestionRequest,
): List<QuestionRequest> = requests.filterNot { it.id == request.id } + request

private fun Throwable.userMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("CLEARTEXT communication", ignoreCase = true) ->
            "Android blocked the HTTP connection. Check the app network security configuration."
        raw.contains("Failed to connect", ignoreCase = true) ->
            "Cannot reach the OpenCode server. Check the address, firewall, and server hostname."
        raw.contains("timeout", ignoreCase = true) ->
            "The OpenCode server did not respond in time."
        raw.isNotBlank() -> raw
        else -> this::class.simpleName ?: "Unknown error"
    }
}

private suspend fun <T> snapshotValue(empty: T, block: suspend () -> T): T? {
    return try {
        block()
    } catch (error: OpenCodeApiException) {
        when (error.statusCode) {
            401, 403 -> throw error
            404, 405 -> empty
            else -> null
        }
    } catch (_: IOException) {
        null
    }
}

private fun directoryKey(value: String?): String = value?.let(::normalizePath) ?: "<default>"

private val popularProviderOrder = listOf(
    "opencode",
    "opencode-go",
    "anthropic",
    "github-copilot",
    "openai",
    "google",
    "openrouter",
    "vercel",
)

private fun visibleModels(
    models: List<ModelChoice>,
    bridge: DesktopBridgeState?,
): List<ModelChoice> {
    val overrides = bridge?.modelPreferences.orEmpty().associate {
        "${it.providerId}/${it.modelId}" to it.visibility
    }
    val now = LocalDate.now()
    val cutoff = now.minusMonths(6)
    val futureCutoff = now.plusMonths(6)
    val latestByFamily = models.mapNotNull { model ->
        val date = parseReleaseDate(model.releaseDate) ?: return@mapNotNull null
        if (!date.isAfter(cutoff) || !date.isBefore(futureCutoff)) return@mapNotNull null
        Triple(model.providerId, model.family, model to date)
    }.groupBy { it.first to it.second }.values.mapNotNull { group ->
        group.maxByOrNull { it.third.second }?.third?.first?.key
    }.toSet()
    return models.filter { model ->
        when (overrides[model.key]) {
            "hide" -> false
            "show" -> true
            else -> model.key in latestByFamily || parseReleaseDate(model.releaseDate) == null
        }
    }.sortedWith(
        compareBy<ModelChoice> { model ->
            val index = popularProviderOrder.indexOf(model.providerId)
            if (index >= 0) index else popularProviderOrder.size
        }.thenBy { model ->
            if (model.providerId in popularProviderOrder) "" else model.providerName.lowercase()
        }.thenBy { it.modelName.lowercase() },
    )
}

private fun resolveDefaultModel(
    catalog: ModelCatalog,
    bridge: DesktopBridgeState?,
    agentModel: ai.opencode.mobile.data.SessionModel? = null,
): ModelChoice? {
    val candidates = buildList {
        agentModel?.let { add("${it.providerId}/${it.modelId}") }
        catalog.defaultModelKey?.let(::add)
        addAll(bridge?.recentModelKeys.orEmpty())
    }
    return candidates.firstNotNullOfOrNull { key -> catalog.models.firstOrNull { it.key == key } }
        ?: catalog.models.firstOrNull()
}

private fun parseReleaseDate(value: String): LocalDate? {
    if (value.isBlank()) return null
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun sameServerIdentity(first: ConnectionProfile, second: ConnectionProfile): Boolean =
    runCatching { OpenCodeApi.normalizeBaseUrl(first.baseUrl) }.getOrNull() ==
        runCatching { OpenCodeApi.normalizeBaseUrl(second.baseUrl) }.getOrNull() &&
        directoryKey(first.directory.takeIf { it.isNotBlank() }) ==
        directoryKey(second.directory.takeIf { it.isNotBlank() }) &&
        first.bridgeBaseUrl == second.bridgeBaseUrl &&
        first.bridgeToken == second.bridgeToken &&
        first.username.ifBlank { "opencode" } == second.username.ifBlank { "opencode" } &&
        first.password == second.password

private fun normalizePath(value: String): String {
    val normalized = value.replace('\\', '/').trimEnd('/')
    val windowsPath = Regex("^[A-Za-z]:/").containsMatchIn(normalized) || normalized.startsWith("//")
    return if (windowsPath) normalized.lowercase() else normalized
}
