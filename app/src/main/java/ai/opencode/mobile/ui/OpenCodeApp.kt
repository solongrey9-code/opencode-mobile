package ai.opencode.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.opencode.mobile.R
import ai.opencode.mobile.data.AgentChoice
import ai.opencode.mobile.data.ConnectionProfile
import ai.opencode.mobile.data.FileDiff
import ai.opencode.mobile.data.MessageInfo
import ai.opencode.mobile.data.MessagePart
import ai.opencode.mobile.data.ModelChoice
import ai.opencode.mobile.data.OpenCodeQuestion
import ai.opencode.mobile.data.OpenCodeSession
import ai.opencode.mobile.data.OpenCodeTodo
import ai.opencode.mobile.data.PermissionRequest
import ai.opencode.mobile.data.QuestionRequest
import ai.opencode.mobile.data.SessionMessage
import ai.opencode.mobile.data.SessionStatus
import ai.opencode.mobile.ui.theme.OcBlue
import ai.opencode.mobile.ui.theme.OcBlueSoft
import ai.opencode.mobile.ui.theme.OcFaint
import ai.opencode.mobile.ui.theme.OcGreen
import ai.opencode.mobile.ui.theme.OcPeach
import ai.opencode.mobile.ui.theme.OcRed
import ai.opencode.mobile.ui.theme.OcYellow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun OpenCodeApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state.screen) {
            AppScreen.CONNECT -> ConnectionScreen(state, viewModel)
            AppScreen.SESSIONS -> SessionsScreen(state, viewModel)
            AppScreen.SESSION -> SessionScreen(state, viewModel)
            AppScreen.WEB -> FullWebScreen(state, viewModel)
        }
    }
    state.error?.let { message ->
        ErrorDialog(message = message, onDismiss = viewModel::clearError)
    }
}

@Composable
private fun ConnectionScreen(state: AppUiState, viewModel: AppViewModel) {
    var baseUrl by rememberSaveable(state.profile.baseUrl) { mutableStateOf(state.profile.baseUrl) }
    var directory by rememberSaveable(state.profile.directory) { mutableStateOf(state.profile.directory) }
    var username by rememberSaveable(state.profile.username) { mutableStateOf(state.profile.username) }
    var password by remember(state.profile.password) { mutableStateOf(state.profile.password) }
    var bridgeBaseUrl by rememberSaveable(state.profile.bridgeBaseUrl) { mutableStateOf(state.profile.bridgeBaseUrl) }
    var bridgeToken by remember(state.profile.bridgeToken) { mutableStateOf(state.profile.bridgeToken) }
    val connecting = state.phase == ConnectionPhase.CONNECTING
    val isHttp = !baseUrl.trim().lowercase().startsWith("https://")
    val canReturn = state.health != null && state.phase in setOf(
        ConnectionPhase.CONNECTED,
        ConnectionPhase.RECONNECTING,
    )
    BackHandler(enabled = canReturn && !connecting) { viewModel.showSessions() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            OpenCodeMark()
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.connect_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.connect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 420.dp),
            )
        }
        item {
            RaisedPanel(Modifier.widthIn(max = 520.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = stringResource(R.string.server_url),
                        singleLine = true,
                        enabled = !connecting,
                        keyboardType = KeyboardType.Uri,
                    )
                    AppTextField(
                        value = directory,
                        onValueChange = { directory = it },
                        label = stringResource(R.string.directory),
                        singleLine = true,
                        enabled = !connecting,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = stringResource(R.string.username),
                            singleLine = true,
                            enabled = !connecting,
                            modifier = Modifier.weight(1f),
                        )
                        AppTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.password),
                            singleLine = true,
                            enabled = !connecting,
                            password = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppTextField(
                            value = bridgeBaseUrl,
                            onValueChange = { bridgeBaseUrl = it },
                            label = stringResource(R.string.bridge_url),
                            singleLine = true,
                            enabled = !connecting,
                            keyboardType = KeyboardType.Uri,
                            modifier = Modifier.weight(1f),
                        )
                        AppTextField(
                            value = bridgeToken,
                            onValueChange = { bridgeToken = it },
                            label = stringResource(R.string.bridge_token),
                            singleLine = true,
                            enabled = !connecting,
                            password = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (isHttp && !isUsbLocalAddress(baseUrl)) {
                        WarningStrip(stringResource(R.string.insecure_http_warning))
                    }
                    if (isUsbLocalAddress(baseUrl)) {
                        WarningStrip(stringResource(R.string.usb_connection_only))
                    }
                    val fallbackUrl = state.desktopBridge?.serverUrls?.firstOrNull()
                    fallbackUrl?.let { lanUrl ->
                        Surface(
                            color = OcBlue.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, OcBlue.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    baseUrl = lanUrl
                                    password = ""
                                },
                        ) {
                            Text(
                                text = stringResource(R.string.lan_available, lanUrl),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(9.dp),
                            )
                        }
                    }
                    Button(
                        onClick = {
                            viewModel.connect(
                                ConnectionProfile(
                                    baseUrl = baseUrl,
                                    bridgeBaseUrl = bridgeBaseUrl,
                                    bridgeToken = bridgeToken,
                                    directory = directory,
                                    username = username,
                                    password = password,
                                ),
                            )
                        },
                        enabled = !connecting && baseUrl.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (connecting) R.string.connecting else R.string.connect))
                    }
                    if (canReturn && !connecting) {
                        TextButton(
                            onClick = viewModel::showSessions,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.back))
                        }
                    }
                }
            }
        }
        item {
            RaisedPanel(Modifier.widthIn(max = 520.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, tint = OcPeach)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.connection_help),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    SetupInstruction("USB", stringResource(R.string.usb_setup))
                    SetupInstruction("LAN", stringResource(R.string.lan_setup))
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(state: AppUiState, viewModel: AppViewModel) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val query = state.searchQuery.trim()
    val sharedOpen = state.desktopBridge?.sharedOpenSessionIds.orEmpty()
    val privateOpen = state.desktopBridge?.privateOpenSessionIds.orEmpty()
    val filtered = remember(state.sessions, state.activeSessionIds, state.desktopBridge, query) {
        val matches = if (query.isEmpty()) state.sessions
        else state.sessions.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.directory.contains(query, ignoreCase = true)
        }
        matches.sortedWith(
            compareByDescending<OpenCodeSession> { it.id in state.activeSessionIds }
                .thenByDescending { it.id == state.desktopBridge?.sharedRecentSessionId }
                .thenByDescending { it.id in sharedOpen }
                .thenByDescending { it.id == state.desktopBridge?.privateRecentSessionId }
                .thenByDescending { it.id in privateOpen }
                .thenByDescending { it.updatedAt },
        )
    }
    val todayLabel = stringResource(R.string.today)
    val yesterdayLabel = stringResource(R.string.yesterday)
    val olderLabel = stringResource(R.string.older)
    val groups = remember(filtered, todayLabel, yesterdayLabel, olderLabel) {
        groupSessions(filtered, todayLabel, yesterdayLabel, olderLabel)
    }
    BackHandler(enabled = false) {}

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.sessions),
                subtitle = state.health?.version?.let { stringResource(R.string.server_version, it) },
                leading = { OpenCodeMark(compact = true) },
                actions = {
                    LiveIndicator(state.phase, state.streamConnected)
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.new_session))
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh))
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = null)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.full_web)) },
                                leadingIcon = { Icon(Icons.Rounded.Language, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.showWeb()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.showConnections()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.disconnect)) },
                                leadingIcon = { Icon(Icons.Rounded.Close, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.disconnect()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SearchField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                    )
                }
                val desktopBridge = state.desktopBridge
                if (
                    desktopBridge != null &&
                    (desktopBridge.privateRecentSessionId != null || !desktopBridge.desktopUsesSharedServer)
                ) {
                    item {
                        AttentionPanel(color = OcYellow) {
                            Text(
                                stringResource(R.string.desktop_not_shared),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                stringResource(R.string.switch_desktop_server),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val running = filtered.filter { state.statusFor(it.id).type != SessionStatus.Type.IDLE }
                val desktopOpen = filtered.filter {
                    state.statusFor(it.id).type == SessionStatus.Type.IDLE &&
                        it.id in sharedOpen
                }
                val desktopPrivate = filtered.filter {
                    state.statusFor(it.id).type == SessionStatus.Type.IDLE &&
                        it.id !in sharedOpen && it.id in privateOpen
                }
                val remaining = filtered.filter {
                    state.statusFor(it.id).type == SessionStatus.Type.IDLE &&
                        it.id !in sharedOpen && it.id !in privateOpen
                }
                if (running.isNotEmpty()) {
                    item(key = "running-label") { SectionLabel(stringResource(R.string.running_sessions)) }
                    items(running, key = { "running-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            status = state.statusFor(session.id),
                            attention = state.permissions.any { it.sessionId == session.id } ||
                                state.questions.any { it.sessionId == session.id },
                            desktopOpen = session.id in sharedOpen,
                            desktopCurrent = session.id == state.desktopBridge?.sharedRecentSessionId,
                            desktopPrivate = session.id in privateOpen,
                            desktopPrivateRecent = session.id == state.desktopBridge?.privateRecentSessionId,
                            onClick = { viewModel.openSession(session.id) },
                        )
                    }
                }
                if (desktopOpen.isNotEmpty()) {
                    item(key = "desktop-label") { SectionLabel(stringResource(R.string.desktop_open_sessions)) }
                    items(desktopOpen, key = { "desktop-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            status = state.statusFor(session.id),
                            attention = state.permissions.any { it.sessionId == session.id } ||
                                state.questions.any { it.sessionId == session.id },
                            desktopOpen = true,
                            desktopCurrent = session.id == state.desktopBridge?.sharedRecentSessionId,
                            desktopPrivate = false,
                            desktopPrivateRecent = false,
                            onClick = { viewModel.openSession(session.id) },
                        )
                    }
                }
                if (desktopPrivate.isNotEmpty()) {
                    item(key = "desktop-private-label") {
                        SectionLabel(stringResource(R.string.desktop_private_sessions))
                    }
                    items(desktopPrivate, key = { "desktop-private-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            status = state.statusFor(session.id),
                            attention = state.permissions.any { it.sessionId == session.id } ||
                                state.questions.any { it.sessionId == session.id },
                            desktopOpen = false,
                            desktopCurrent = false,
                            desktopPrivate = true,
                            desktopPrivateRecent = session.id == state.desktopBridge?.privateRecentSessionId,
                            onClick = { viewModel.openSession(session.id) },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.FolderOpen,
                            title = stringResource(R.string.no_sessions),
                        )
                    }
                }
                groupSessions(remaining, todayLabel, yesterdayLabel, olderLabel).forEach { group ->
                    item(key = group.label) { SectionLabel(group.label) }
                    items(group.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            status = state.statusFor(session.id),
                            attention = state.permissions.any { it.sessionId == session.id } ||
                                state.questions.any { it.sessionId == session.id },
                            desktopOpen = false,
                            desktopCurrent = false,
                            desktopPrivate = false,
                            desktopPrivateRecent = false,
                            onClick = { viewModel.openSession(session.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        NewSessionDialog(
            directories = state.sessions.map { it.directory }
                .filter { it.isNotBlank() }
                .distinct(),
            initialDirectory = state.profile.directory,
            onDismiss = { showCreate = false },
            onConfirm = { title, directory ->
                showCreate = false
                viewModel.createSession(title, directory)
            },
        )
    }
}

@Composable
private fun SessionScreen(state: AppUiState, viewModel: AppViewModel) {
    val session = state.selectedSession
    if (session == null) {
        LaunchedEffect(Unit) { viewModel.showSessions() }
        return
    }
    BackHandler { viewModel.showSessions() }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AppTopBar(
                title = session.title,
                subtitle = session.directory.substringAfterLast('/').substringAfterLast('\\')
                    .ifBlank { session.directory },
                leading = {
                    IconButton(onClick = viewModel::showSessions) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    SessionStatusDot(state.selectedStatus)
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = null)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = {
                                    showMenu = false
                                    showRename = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (session.shareUrl == null) R.string.share else R.string.unshare))
                                },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.toggleShare(session.id)
                                },
                            )
                            session.shareUrl?.let { url ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_link)) },
                                    leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                    onClick = {
                                        copyText(context, "OpenCode share", url)
                                        showMenu = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.full_web)) },
                                leadingIcon = { Icon(Icons.Rounded.Language, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.showWeb()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showMenu = false
                                    showDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.pane == SessionPane.TIMELINE) {
                Composer(
                    state = state,
                    onTextChange = viewModel::setComposerText,
                    onSend = viewModel::sendPrompt,
                    onAbort = viewModel::abort,
                    onAgent = viewModel::selectAgent,
                    onModel = viewModel::selectModel,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            SessionPaneSwitcher(
                selected = state.pane,
                changeCount = state.detail.diffs.size,
                onSelect = viewModel::setPane,
            )
            when (state.pane) {
                SessionPane.TIMELINE -> Timeline(
                    state = state,
                    listState = listState,
                    onPermission = viewModel::replyPermission,
                    onQuestion = viewModel::replyQuestion,
                    onRejectQuestion = viewModel::rejectQuestion,
                    onRevert = viewModel::revert,
                )
                SessionPane.CHANGES -> ChangesList(state.detail.diffs)
            }
        }
    }

    if (showRename) {
        TextInputDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.new_session_title),
            initialValue = session.title,
            confirmText = stringResource(R.string.rename),
            onDismiss = { showRename = false },
            onConfirm = {
                showRename = false
                viewModel.renameSession(session.id, it)
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        viewModel.deleteSession(session.id)
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Timeline(
    state: AppUiState,
    listState: LazyListState,
    onPermission: (String, String) -> Unit,
    onQuestion: (String, List<List<String>>) -> Unit,
    onRejectQuestion: (String) -> Unit,
    onRevert: (String, String?) -> Unit,
) {
    val messages = state.detail.messages
    val timelineItemCount =
        (if (state.selectedPermission != null) 1 else 0) +
            (if (state.selectedQuestion != null) 1 else 0) +
            (if (state.detail.todos.isNotEmpty()) 1 else 0) +
            messages.size +
            (if (state.selectedStatus.type == SessionStatus.Type.BUSY) 1 else 0)
    LaunchedEffect(
        timelineItemCount,
        messages.lastOrNull()?.parts?.lastOrNull()?.text?.length,
    ) {
        if (timelineItemCount > 0) {
            listState.animateScrollToItem(timelineItemCount - 1)
        }
    }
    if (state.detail.loading && messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 10.dp,
            bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.selectedPermission?.let { request ->
            item(key = "permission-${request.id}") {
                PermissionDock(request, onPermission)
            }
        }
        state.selectedQuestion?.let { request ->
            item(key = "question-${request.id}") {
                QuestionDock(request, onQuestion, onRejectQuestion)
            }
        }
        if (state.detail.todos.isNotEmpty()) {
            item(key = "todos") { TodoPanel(state.detail.todos) }
        }
        items(messages, key = { it.info.id }) { message ->
            MessageTurn(message, onRevert)
        }
        if (state.selectedStatus.type == SessionStatus.Type.BUSY) {
            item(key = "working") { WorkingIndicator() }
        }
    }
}

@Composable
private fun MessageTurn(
    message: SessionMessage,
    onRevert: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    if (message.info.role == MessageInfo.Role.USER) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    message.parts.forEach { part -> UserPart(part) }
                }
            }
            Row {
                MiniAction(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.copy),
                    onClick = { copyText(context, "OpenCode prompt", message.plainText()) },
                )
                MiniAction(
                    icon = Icons.Rounded.Replay,
                    label = stringResource(R.string.revert_here),
                    onClick = { onRevert(message.info.id, null) },
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentGlyph(message.info.agent)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = message.info.agent?.replaceFirstChar { it.uppercase() } ?: "OpenCode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                message.info.modelId?.let {
                    Text(
                        text = it.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = OcFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            message.parts.forEach { part -> AssistantPart(part) }
            message.info.errorMessage?.let { ErrorStrip(it) }
            if (message.parts.isNotEmpty()) {
                MiniAction(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.copy),
                    onClick = { copyText(context, "OpenCode response", message.plainText()) },
                )
            }
        }
    }
}

@Composable
private fun UserPart(part: MessagePart) {
    when (part.type) {
        "text" -> SelectionContainer { MarkdownText(part.text.orEmpty()) }
        "file" -> FileChip(part)
        "agent" -> Text("@${part.raw["name"]}", color = MaterialTheme.colorScheme.primary)
        else -> part.text?.let { Text(it) }
    }
}

@Composable
private fun AssistantPart(part: MessagePart) {
    when (part.type) {
        "text" -> if (!part.text.isNullOrBlank()) {
            SelectionContainer { MarkdownText(part.text) }
        }
        "reasoning" -> ExpandablePart(
            title = stringResource(R.string.reasoning),
            icon = Icons.Rounded.Bolt,
            tint = OcPeach,
        ) {
            SelectionContainer {
                Text(
                    text = part.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        "tool" -> ToolPartView(part)
        "file" -> FileChip(part)
        "patch" -> ToolSummary(
            icon = Icons.Rounded.Code,
            title = "Patch",
            subtitle = part.files.joinToString(),
            tint = OcGreen,
        )
        "step-start" -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        "step-finish" -> part.reason?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = OcFaint,
            )
        }
        "retry" -> WarningStrip(part.raw.toString())
        else -> Unit
    }
}

@Composable
private fun ToolPartView(part: MessagePart) {
    val statusColor = when (part.toolStatus) {
        "completed" -> OcGreen
        "error" -> OcRed
        "running" -> OcYellow
        else -> OcFaint
    }
    ExpandablePart(
        title = part.toolTitle ?: part.toolName ?: stringResource(R.string.tool),
        subtitle = part.toolStatus,
        icon = when (part.toolName) {
            "bash", "shell" -> Icons.Rounded.Terminal
            "read", "write", "edit" -> Icons.Rounded.Description
            else -> Icons.Rounded.Code
        },
        tint = statusColor,
        initiallyExpanded = part.toolStatus == "running" || part.toolStatus == "error",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            part.toolInput?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                CodeBlock(it)
            }
            part.toolOutput?.takeIf { it.isNotBlank() }?.let { CodeBlock(it) }
            part.toolError?.takeIf { it.isNotBlank() }?.let { ErrorStrip(it) }
        }
    }
}

@Composable
private fun PermissionDock(
    request: PermissionRequest,
    onReply: (String, String) -> Unit,
) {
    AttentionPanel(color = OcYellow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, tint = OcYellow)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.permission_required),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(request.permission, style = MaterialTheme.typography.labelLarge)
        request.patterns.forEach { pattern -> CodeBlock(pattern) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onReply(request.id, "reject") },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.reject)) }
            OutlinedButton(
                onClick = { onReply(request.id, "always") },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.always_allow)) }
            Button(
                onClick = { onReply(request.id, "once") },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.allow_once)) }
        }
    }
}

@Composable
private fun QuestionDock(
    request: QuestionRequest,
    onReply: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
) {
    var index by rememberSaveable(request.id) { mutableIntStateOf(0) }
    val selections = remember(request.id) { mutableStateMapOf<Int, Set<String>>() }
    val customAnswers = remember(request.id) { mutableStateMapOf<Int, String>() }
    val question = request.questions.getOrNull(index) ?: return
    val chosen = selections[index].orEmpty()
    val custom = customAnswers[index].orEmpty()
    val canContinue = chosen.isNotEmpty() || custom.isNotBlank()

    AttentionPanel(color = OcBlue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.question_required),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${index + 1}/${request.questions.size}",
                style = MaterialTheme.typography.labelMedium,
                color = OcFaint,
            )
        }
        LinearProgressIndicator(
            progress = { (index + 1f) / request.questions.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(question.header, style = MaterialTheme.typography.labelMedium, color = OcFaint)
        Text(question.question, style = MaterialTheme.typography.bodyLarge)
        question.options.forEach { option ->
            QuestionOptionRow(
                option = option.label,
                description = option.description,
                selected = option.label in chosen,
                onClick = {
                    selections[index] = if (question.multiple) {
                        if (option.label in chosen) chosen - option.label else chosen + option.label
                    } else setOf(option.label)
                    customAnswers.remove(index)
                },
            )
        }
        if (question.custom) {
            AppTextField(
                value = custom,
                onValueChange = {
                    customAnswers[index] = it
                    if (it.isNotBlank()) selections.remove(index)
                },
                label = stringResource(R.string.custom_answer),
                singleLine = false,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { onReject(request.id) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.skip_question)) }
            if (index > 0) {
                OutlinedButton(onClick = { index-- }) { Text(stringResource(R.string.back)) }
            }
            Button(
                onClick = {
                    if (index < request.questions.lastIndex) {
                        index++
                    } else {
                        val answers = request.questions.indices.map { questionIndex ->
                            customAnswers[questionIndex]
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::listOf)
                                ?: selections[questionIndex].orEmpty().toList()
                        }
                        onReply(request.id, answers)
                    }
                },
                enabled = canContinue,
            ) {
                Text(
                    stringResource(
                        if (index < request.questions.lastIndex) R.string.send
                        else R.string.submit_answer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuestionOptionRow(
    option: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else OcFaint, CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(option, style = MaterialTheme.typography.labelLarge)
                if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoPanel(todos: List<OpenCodeTodo>) {
    ExpandablePart(
        title = stringResource(R.string.todos),
        subtitle = "${todos.count { it.status == "completed" }}/${todos.size}",
        icon = Icons.Rounded.Check,
        tint = OcGreen,
        initiallyExpanded = todos.any { it.status == "in_progress" },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            todos.forEach { todo ->
                Row(verticalAlignment = Alignment.Top) {
                    val color = when (todo.status) {
                        "completed" -> OcGreen
                        "in_progress" -> OcYellow
                        "cancelled" -> OcFaint
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (todo.status == "cancelled") OcFaint else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: AppUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onAgent: (String?) -> Unit,
    onModel: (ModelChoice?) -> Unit,
) {
    val busy = state.selectedStatus.type == SessionStatus.Type.BUSY ||
        state.selectedStatus.type == SessionStatus.Type.RETRY
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                BasicTextField(
                    value = state.composerText,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 2,
                    maxLines = 7,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeightIn(min = 56.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                                onSend()
                                true
                            } else false
                        },
                    decorationBox = { inner ->
                        Box {
                            if (state.composerText.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.message_hint),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                    color = OcFaint,
                                )
                            }
                            inner()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentSelector(state.agents, state.selectedAgent, onAgent)
                    Spacer(Modifier.width(4.dp))
                    ModelSelector(state.models, state.selectedModel, onModel)
                    Spacer(Modifier.weight(1f))
                    if (busy) {
                        IconButton(
                            onClick = onAbort,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Rounded.Stop, stringResource(R.string.stop), Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(
                            onClick = onSend,
                            enabled = state.composerText.isNotBlank() && !state.actionInProgress,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.size(36.dp),
                        ) {
                            if (state.actionInProgress) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.send), Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSelector(
    agents: List<AgentChoice>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CompactSelector(
            icon = Icons.Rounded.Bolt,
            text = selected?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.agent),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.default_option)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            agents.filter { it.mode != "subagent" }.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(agent.name.replaceFirstChar { it.uppercase() })
                            if (agent.description.isNotBlank()) {
                                Text(
                                    agent.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelSelector(
    models: List<ModelChoice>,
    selected: ModelChoice?,
    onSelect: (ModelChoice?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visible = remember(models, query) {
        models.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
            .take(80)
    }
    Box {
        CompactSelector(
            icon = Icons.Rounded.UnfoldMore,
            text = selected?.modelId?.substringAfterLast('/') ?: stringResource(R.string.model),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            modifier = Modifier.widthIn(min = 280.dp, max = 380.dp),
        ) {
            if (models.size > 12) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.model),
                    singleLine = true,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            visible.groupBy { it.providerId }.forEach { (_, providerModels) ->
                Text(
                    text = providerModels.first().providerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = OcFaint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
                providerModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    model.modelName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model.latest) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.latest_model),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OcBlueSoft,
                                    )
                                }
                            }
                        },
                        trailingIcon = if (selected?.key == model.key) {
                            {
                                Icon(
                                    Icons.Rounded.Check,
                                    stringResource(R.string.current_model),
                                    Modifier.size(16.dp),
                                )
                            }
                        } else null,
                        onClick = {
                            onSelect(model)
                            expanded = false
                            query = ""
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSelector(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 100.dp),
        )
    }
}

@Composable
private fun ChangesList(diffs: List<FileDiff>) {
    if (diffs.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Code,
            title = stringResource(R.string.no_changes),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(diffs, key = { it.file }) { diff -> DiffCard(diff) }
    }
}

@Composable
private fun DiffCard(diff: FileDiff) {
    var expanded by rememberSaveable(diff.file) { mutableStateOf(true) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Description, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = diff.file,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("+${diff.additions}", color = OcGreen, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text("-${diff.deletions}", color = OcRed, style = MaterialTheme.typography.labelMedium)
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (diff.patch.isBlank()) {
                        Text(
                            text = diff.status ?: "Modified",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = OcFaint,
                        )
                    } else {
                        DiffText(diff.patch)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffText(patch: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        patch.lines().forEach { line ->
            val background = when {
                line.startsWith("+") && !line.startsWith("+++") -> OcGreen.copy(alpha = 0.13f)
                line.startsWith("-") && !line.startsWith("---") -> OcRed.copy(alpha = 0.13f)
                line.startsWith("@@") -> OcBlue.copy(alpha = 0.13f)
                else -> Color.Transparent
            }
            val foreground = when {
                line.startsWith("+") && !line.startsWith("+++") -> OcGreen
                line.startsWith("-") && !line.startsWith("---") -> OcRed
                line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                text = line.ifEmpty { " " },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = foreground,
                ),
                modifier = Modifier
                    .background(background)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun FullWebScreen(state: AppUiState, viewModel: AppViewModel) {
    BackHandler { if (state.selectedSessionId != null) viewModel.openSession(state.selectedSessionId) else viewModel.showSessions() }
    val context = LocalContext.current
    val webUrl = viewModel.webUrl()
    fun back() {
        if (state.selectedSessionId != null) viewModel.openSession(state.selectedSessionId)
        else viewModel.showSessions()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.full_web),
                subtitle = stringResource(R.string.full_web_hint),
                leading = {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {},
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Language,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.full_web),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.browser_auth_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    runCatching {
                        CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .build()
                            .launchUrl(context, webUrl.toUri())
                    }.onFailure {
                        viewModel.reportError(context.getString(R.string.browser_unavailable))
                    }
                },
                modifier = Modifier.height(44.dp),
            ) {
                Icon(Icons.Rounded.Language, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.open_in_browser))
            }
        }
    }
}

@Composable
private fun SessionPaneSwitcher(
    selected: SessionPane,
    changeCount: Int,
    onSelect: (SessionPane) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SegmentButton(
            text = stringResource(R.string.session_tab),
            selected = selected == SessionPane.TIMELINE,
            onClick = { onSelect(SessionPane.TIMELINE) },
        )
        SegmentButton(
            text = stringResource(R.string.changes_tab, changeCount),
            selected = selected == SessionPane.CHANGES,
            onClick = { onSelect(SessionPane.CHANGES) },
        )
    }
}

@Composable
private fun RowScope.SegmentButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SessionRow(
    session: OpenCodeSession,
    status: SessionStatus?,
    attention: Boolean,
    desktopOpen: Boolean,
    desktopCurrent: Boolean,
    desktopPrivate: Boolean,
    desktopPrivateRecent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectAvatar(session.projectId, status?.type == SessionStatus.Type.BUSY)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (attention) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(OcYellow))
                    }
                    if (desktopOpen) {
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(5.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (desktopCurrent) R.string.desktop_current_badge
                                    else R.string.desktop_open_badge,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (desktopPrivate) {
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            color = OcYellow.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(5.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (desktopPrivateRecent) R.string.desktop_private_recent_badge
                                    else R.string.desktop_private_badge,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = OcYellow,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = session.directory,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                status?.takeIf { it.type != SessionStatus.Type.IDLE }?.let { runningStatus ->
                    Text(
                        text = if (runningStatus.type == SessionStatus.Type.RETRY && runningStatus.attempt != null) {
                            stringResource(R.string.retrying_attempt, runningStatus.attempt)
                        } else {
                            stringResource(
                                if (runningStatus.type == SessionStatus.Type.RETRY) R.string.retrying_session
                                else R.string.busy,
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (runningStatus.type == SessionStatus.Type.RETRY) OcRed else OcYellow,
                    )
                    runningStatus.message?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = OcFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp),
                        )
                    }
                }
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = OcFaint,
                )
                session.summary?.let {
                    Text(
                        text = "+${it.additions} -${it.deletions}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.files > 0) OcGreen else OcFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectAvatar(projectId: String, running: Boolean) {
    val seed = projectId.fold(0) { acc, char -> acc * 31 + char.code }
    val colors = listOf(OcBlue, OcPeach, Color(0xFF9E99F7), OcGreen, Color(0xFFF799C6))
    val color = colors[kotlin.math.abs(seed % colors.size)]
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = projectId.takeLast(1).uppercase().ifBlank { "O" },
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
        }
        if (running) PulsingDot()
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "running")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(OcYellow))
    }
}

@Composable
private fun SessionStatusDot(status: SessionStatus) {
    val color = when (status.type) {
        SessionStatus.Type.IDLE -> OcGreen
        SessionStatus.Type.BUSY -> OcYellow
        SessionStatus.Type.RETRY -> OcRed
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun LiveIndicator(phase: ConnectionPhase, streamConnected: Boolean) {
    val (color, label) = when {
        phase == ConnectionPhase.RECONNECTING -> OcYellow to stringResource(R.string.reconnecting)
        phase == ConnectionPhase.CONNECTED && streamConnected -> OcGreen to stringResource(R.string.live)
        phase == ConnectionPhase.CONNECTED -> OcYellow to stringResource(R.string.connecting)
        else -> OcRed to stringResource(R.string.offline)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppTopBar(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .height(54.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) { leading() }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OcFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
private fun OpenCodeMark(compact: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(if (compact) 32.dp else 58.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "[•]",
                color = OcPeach,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 14.sp else 23.sp,
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = OcFaint)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                stringResource(R.string.search_sessions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OcFaint,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Close,
                    null,
                    Modifier
                        .size(18.dp)
                        .clickable { onValueChange("") },
                    tint = OcFaint,
                )
            }
        }
    }
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(9.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RaisedPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        content = content,
    )
}

@Composable
private fun AttentionPanel(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun ExpandablePart(
    title: String,
    icon: ImageVector,
    tint: Color,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = tint)
                Spacer(Modifier.width(7.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = OcFaint)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Rounded.Close else Icons.Rounded.UnfoldMore,
                    null,
                    Modifier.size(15.dp),
                    tint = OcFaint,
                )
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolSummary(icon: ImageVector, title: String, subtitle: String, tint: Color) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(7.dp))
        Text(title, style = MaterialTheme.typography.labelMedium)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.width(7.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OcFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileChip(part: MessagePart) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(7.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Description, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(part.filename ?: part.url.orEmpty(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CodeBlock(text: String) {
    SelectionContainer {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(7.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(9.dp),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { splitMarkdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                MarkdownKind.CODE -> CodeBlock(block.text)
                MarkdownKind.HEADING -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.titleMedium,
                )
                MarkdownKind.QUOTE -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                        .padding(start = 9.dp, top = 2.dp, bottom = 2.dp),
                )
                MarkdownKind.TEXT -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun MiniAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = OcFaint)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = OcFaint)
    }
}

@Composable
private fun AgentGlyph(agent: String?) {
    val color = when (agent?.lowercase()) {
        "plan" -> Color(0xFFF799C6)
        "build" -> Color(0xFFC3D4FD)
        "explore" -> Color(0xFFF7E5B5)
        "review" -> Color(0xFFB8E9C1)
        else -> Color(0xFF9E99F7)
    }
    Box(
        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun WorkingIndicator() {
    val transition = rememberInfiniteTransition(label = "working")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "working-alpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(OcYellow))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.busy), style = MaterialTheme.typography.bodySmall, color = OcFaint)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = OcFaint)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = OcFaint)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OcFaint,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 7.dp),
    )
}

@Composable
private fun SetupInstruction(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(3.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WarningStrip(text: String) {
    Surface(
        color = OcYellow.copy(alpha = 0.09f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, OcYellow.copy(alpha = 0.35f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(9.dp),
        )
    }
}

@Composable
private fun ErrorStrip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
    ) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(7.dp))
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.error_title)) },
        text = { SelectionContainer { Text(message) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                label = label,
                singleLine = true,
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    LaunchedEffect(Unit) {
        delay(120)
        focusRequester.requestFocus()
    }
}

@Composable
private fun NewSessionDialog(
    directories: List<String>,
    initialDirectory: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var directory by rememberSaveable(initialDirectory) { mutableStateOf(initialDirectory) }
    var showDirectories by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_session)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(R.string.new_session_title),
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester),
                )
                AppTextField(
                    value = directory,
                    onValueChange = { directory = it },
                    label = stringResource(R.string.project_directory),
                    singleLine = true,
                )
                if (directories.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            onClick = { showDirectories = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.choose_project),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = showDirectories,
                            onDismissRequest = { showDirectories = false },
                            modifier = Modifier.widthIn(min = 300.dp, max = 520.dp),
                        ) {
                            directories.forEach { candidate ->
                                DropdownMenuItem(
                                    text = {
                                        Text(candidate, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    },
                                    onClick = {
                                        directory = candidate
                                        showDirectories = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (directory.isBlank()) {
                    Text(
                        stringResource(R.string.project_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, directory) },
                enabled = directory.isNotBlank(),
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    LaunchedEffect(Unit) {
        delay(120)
        focusRequester.requestFocus()
    }
}

private data class SessionGroup(val label: String, val sessions: List<OpenCodeSession>)

private fun groupSessions(
    sessions: List<OpenCodeSession>,
    todayLabel: String,
    yesterdayLabel: String,
    olderLabel: String,
): List<SessionGroup> {
    val zone = java.time.ZoneId.systemDefault()
    val todayDate = java.time.LocalDate.now(zone)
    val yesterdayDate = todayDate.minusDays(1)
    val today = sessions.filter { session ->
        session.updatedAt.toLocalDate(zone) == todayDate
    }
    val yesterday = sessions.filter { session ->
        session.updatedAt.toLocalDate(zone) == yesterdayDate
    }
    val older = sessions.filter { session ->
        val date = session.updatedAt.toLocalDate(zone)
        date != todayDate && date != yesterdayDate
    }
    return buildList {
        if (today.isNotEmpty()) add(SessionGroup(todayLabel, today))
        if (yesterday.isNotEmpty()) add(SessionGroup(yesterdayLabel, yesterday))
        if (older.isNotEmpty()) add(SessionGroup(olderLabel, older))
    }
}

private fun Long.toLocalDate(zone: java.time.ZoneId): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(this.coerceAtLeast(0L)).atZone(zone).toLocalDate()

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
}

private fun isUsbLocalAddress(value: String): Boolean {
    val lower = value.lowercase()
    return "127.0.0.1" in lower || "localhost" in lower || "10.0.2.2" in lower
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun SessionMessage.plainText(): String = parts.joinToString("\n") { part ->
    when (part.type) {
        "text", "reasoning" -> part.text.orEmpty()
        "tool" -> listOfNotNull(part.toolTitle, part.toolOutput, part.toolError).joinToString("\n")
        "file" -> part.filename ?: part.url.orEmpty()
        else -> ""
    }
}.trim()

private enum class MarkdownKind { TEXT, CODE, HEADING, QUOTE }

private data class MarkdownBlock(val kind: MarkdownKind, val text: String)

private fun splitMarkdownBlocks(text: String): List<MarkdownBlock> {
    if (text.isBlank()) return emptyList()
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val joined = paragraph.joinToString("\n").trim()
        val kind = when {
            joined.startsWith("#") -> MarkdownKind.HEADING
            joined.lines().all { it.trimStart().startsWith(">") } -> MarkdownKind.QUOTE
            else -> MarkdownKind.TEXT
        }
        val clean = when (kind) {
            MarkdownKind.HEADING -> joined.trimStart('#', ' ')
            MarkdownKind.QUOTE -> joined.lines().joinToString("\n") { it.trimStart().removePrefix("> ") }
            else -> joined
        }
        result += MarkdownBlock(kind, clean)
        paragraph.clear()
    }

    text.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                result += MarkdownBlock(MarkdownKind.CODE, code.joinToString("\n"))
                code.clear()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
        } else if (inCode) {
            code += line
        } else if (line.isBlank()) {
            flushParagraph()
        } else {
            paragraph += line
        }
    }
    if (inCode && code.isNotEmpty()) result += MarkdownBlock(MarkdownKind.CODE, code.joinToString("\n"))
    flushParagraph()
    return result
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val pattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    pattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val value = match.value
        if (value.startsWith("**")) {
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
            append(value.removePrefix("**").removeSuffix("**"))
            pop()
        } else {
            pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x223B5CF6),
                ),
            )
            append(value.removePrefix("`").removeSuffix("`"))
            pop()
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
