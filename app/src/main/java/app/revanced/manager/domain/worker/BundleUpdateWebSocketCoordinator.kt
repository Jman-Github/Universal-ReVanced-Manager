package app.revanced.manager.domain.worker

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.domain.manager.BundleUpdateDeliveryMode
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.revanced.manager.service.BundleUpdateWebSocketService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BundleUpdateWebSocketCoordinator(
    private val app: Application,
    private val prefs: PreferencesManager,
    private val networkInfo: NetworkInfo,
    private val workerRepository: WorkerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appForeground = MutableStateFlow(false)
    private val started = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }
    private val triggerMutex = Mutex()
    private val webSocketClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var desiredState = DesiredState.NONE

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var lastSocketMessageAt = 0L

    @Volatile
    private var lastCompletedRefreshCursor: String? = null

    @Volatile
    private var lastTriggerAt = 0L

    @Volatile
    private var endpointIndex = 0

    @Volatile
    private var cursorLoaded = false

    private var socketLoopJob: Job? = null
    private var foregroundServiceRunning = false
    private var foregroundListenState: ForegroundListenState? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                prefs.searchForManagerUpdatesBackgroundInterval.flow,
                prefs.searchForUpdatesBackgroundInterval.flow,
                prefs.bundleUpdateDeliveryMode.flow,
                appForeground
            ) { managerInterval, bundleInterval, mode, isForeground ->
                DesiredState.from(managerInterval, bundleInterval, mode, isForeground)
            }.distinctUntilChanged()
                .collect { state ->
                    desiredState = state
                    applyDesiredState(state)
                }
        }
    }

    fun onAppForegroundChanged(isForeground: Boolean) {
        appForeground.value = isForeground
    }

    private suspend fun applyDesiredState(state: DesiredState) {
        ensureForegroundServiceRunning(state)

        if (state.shouldRunSocket) {
            startSocketLoop()
        } else {
            stopSocketLoop()
        }
    }

    private suspend fun ensureForegroundServiceRunning(state: DesiredState) {
        val shouldRun = state.requiresForegroundService
        val targetListenState = if (shouldRun) {
            ForegroundListenState(
                listenForBundle = state.listenForBundle,
                listenForManager = state.listenForManager
            )
        } else {
            null
        }

        if (shouldRun == foregroundServiceRunning &&
            (!shouldRun || foregroundListenState == targetListenState)
        ) {
            return
        }

        val intent = Intent(app, BundleUpdateWebSocketService::class.java).apply {
            putExtra(BundleUpdateWebSocketService.EXTRA_LISTEN_BUNDLE_UPDATES, state.listenForBundle)
            putExtra(BundleUpdateWebSocketService.EXTRA_LISTEN_MANAGER_UPDATES, state.listenForManager)
        }
        if (shouldRun) {
            runCatching {
                ContextCompat.startForegroundService(app, intent)
                foregroundServiceRunning = true
                foregroundListenState = targetListenState
            }.onFailure {
                Log.w(TAG, "Unable to start websocket foreground service", it)
            }
        } else {
            app.stopService(intent)
            foregroundServiceRunning = false
            foregroundListenState = null
        }
    }

    private fun startSocketLoop() {
        if (socketLoopJob?.isActive == true) return

        socketLoopJob = scope.launch {
            var reconnectAttempt = 0
            while (isActive && desiredState.shouldRunSocket) {
                if (!networkInfo.isConnected()) {
                    delay(NETWORK_RETRY_DELAY_MS)
                    continue
                }

                val endpoint = WEB_SOCKET_ENDPOINTS[endpointIndex % WEB_SOCKET_ENDPOINTS.size]
                val closedSignal = CompletableDeferred<Unit>()
                var protocol = GraphqlProtocol.GRAPHQL_WS
                var subscriptionSent = false

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        activeWebSocket = webSocket
                        protocol = response.header("Sec-WebSocket-Protocol")
                            ?.let(GraphqlProtocol::fromHeader)
                            ?: GraphqlProtocol.GRAPHQL_WS
                        subscriptionSent = false
                        reconnectAttempt = 0
                        lastSocketMessageAt = System.currentTimeMillis()
                        sendConnectionInit(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        lastSocketMessageAt = System.currentTimeMillis()
                        subscriptionSent = handleIncomingMessage(
                            webSocket = webSocket,
                            protocol = protocol,
                            rawMessage = text,
                            subscriptionSent = subscriptionSent
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        if (!closedSignal.isCompleted) closedSignal.complete(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!closedSignal.isCompleted) closedSignal.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Websocket failure on $endpoint", t)
                        if (!closedSignal.isCompleted) closedSignal.complete(Unit)
                    }
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .header("Sec-WebSocket-Protocol", "graphql-transport-ws,graphql-ws")
                    .build()
                val socket = webSocketClient.newWebSocket(request, listener)

                val healthJob = launch {
                    while (!closedSignal.isCompleted) {
                        delay(HEALTH_CHECK_INTERVAL_MS)
                        val idleFor = System.currentTimeMillis() - lastSocketMessageAt
                        if (idleFor >= SOCKET_IDLE_TIMEOUT_MS) {
                            Log.w(TAG, "Websocket idle timeout reached, reconnecting")
                            socket.cancel()
                            if (!closedSignal.isCompleted) closedSignal.complete(Unit)
                        }
                    }
                }

                closedSignal.await()
                healthJob.cancel()
                activeWebSocket = null

                if (!desiredState.shouldRunSocket) break

                reconnectAttempt += 1
                endpointIndex = (endpointIndex + 1) % WEB_SOCKET_ENDPOINTS.size
                delay(backoffMs(reconnectAttempt))
            }
        }
    }

    private suspend fun stopSocketLoop() {
        val runningJob = socketLoopJob
        socketLoopJob = null
        activeWebSocket?.cancel()
        activeWebSocket = null
        runningJob?.cancelAndJoin()
    }

    private fun sendConnectionInit(webSocket: WebSocket) {
        webSocket.send("""{"type":"connection_init","payload":{}}""")
    }

    private fun sendSubscription(webSocket: WebSocket, protocol: GraphqlProtocol) {
        val payload = REFRESH_SUBSCRIPTION_QUERY.replace("\"", "\\\"").replace("\n", "\\n")
        val message = when (protocol) {
            GraphqlProtocol.GRAPHQL_WS -> {
                """{"id":"$SUBSCRIPTION_ID","type":"start","payload":{"query":"$payload"}}"""
            }
            GraphqlProtocol.GRAPHQL_TRANSPORT_WS -> {
                """{"id":"$SUBSCRIPTION_ID","type":"subscribe","payload":{"query":"$payload"}}"""
            }
        }
        webSocket.send(message)
    }

    private fun handleIncomingMessage(
        webSocket: WebSocket,
        protocol: GraphqlProtocol,
        rawMessage: String,
        subscriptionSent: Boolean
    ): Boolean {
        val event = runCatching { json.parseToJsonElement(rawMessage).jsonObject }.getOrNull()
            ?: return subscriptionSent
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "connection_ack" -> {
                if (!subscriptionSent) {
                    sendSubscription(webSocket, protocol)
                    return true
                }
            }
            "ping" -> {
                if (protocol == GraphqlProtocol.GRAPHQL_TRANSPORT_WS) {
                    webSocket.send("""{"type":"pong"}""")
                }
            }
            "ka", "pong" -> Unit
            "data", "next" -> {
                onSubscriptionPayload(event["payload"])
            }
            "error", "connection_error" -> {
                Log.w(TAG, "Websocket protocol error: $rawMessage")
            }
        }
        return subscriptionSent
    }

    private fun onSubscriptionPayload(payload: JsonElement?) {
        val root = payload?.jsonObject ?: return
        val data = root["data"]?.jsonObject ?: return
        val jobs = data["refresh_jobs"]?.jsonArray ?: return
        val firstJob = jobs.firstOrNull()?.jsonObject ?: return
        val startedAt = firstJob["started_at"]?.jsonPrimitive?.contentOrNull ?: return
        val status = firstJob["status"]?.jsonPrimitive?.contentOrNull

        scope.launch {
            triggerMutex.withLock {
                if (!shouldTriggerForStatus(status)) return@withLock

                ensureRefreshCursorLoaded()
                if (lastCompletedRefreshCursor == startedAt) return@withLock

                val now = System.currentTimeMillis()
                if (now - lastTriggerAt < MIN_TRIGGER_INTERVAL_MS) return@withLock

                if (!launchEnabledWebSocketWorkers()) return@withLock
                lastTriggerAt = now
                lastCompletedRefreshCursor = startedAt
                persistRefreshCursor(startedAt)
            }
        }
    }

    private fun shouldTriggerForStatus(status: String?): Boolean {
        val normalized = status?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return true
        return normalized.contains("success") ||
            normalized.contains("complete") ||
            normalized.contains("done") ||
            normalized.contains("finish")
    }

    private fun launchBundleUpdateWorker(): Boolean {
        return runCatching {
            workerRepository.launchBundleUpdateNotificationNow()
            true
        }.onFailure {
            Log.w(TAG, "Failed to enqueue bundle update check from websocket", it)
        }.getOrDefault(false)
    }

    private fun launchManagerUpdateWorker(): Boolean {
        return runCatching {
            workerRepository.launchManagerUpdateNotificationNow()
            true
        }.onFailure {
            Log.w(TAG, "Failed to enqueue manager update check from websocket", it)
        }.getOrDefault(false)
    }

    private suspend fun launchEnabledWebSocketWorkers(): Boolean {
        var launchedAny = false
        if (prefs.searchForUpdatesBackgroundInterval.get() != SearchForUpdatesBackgroundInterval.NEVER) {
            launchedAny = launchBundleUpdateWorker() || launchedAny
        }
        if (prefs.searchForManagerUpdatesBackgroundInterval.get() != SearchForUpdatesBackgroundInterval.NEVER) {
            launchedAny = launchManagerUpdateWorker() || launchedAny
        }
        return launchedAny
    }

    private fun ensureRefreshCursorLoaded() {
        if (cursorLoaded) return
        synchronized(this) {
            if (cursorLoaded) return
            lastCompletedRefreshCursor = runCatching {
                app.getSharedPreferences(CURSOR_PREFS_FILE, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_COMPLETED_REFRESH_CURSOR, null)
            }.getOrElse {
                Log.w(TAG, "Failed to load refresh cursor", it)
                null
            }
            cursorLoaded = true
        }
    }

    private fun persistRefreshCursor(startedAt: String) {
        runCatching {
            app.getSharedPreferences(CURSOR_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_COMPLETED_REFRESH_CURSOR, startedAt)
                .apply()
        }.onFailure {
            Log.w(TAG, "Failed to persist refresh cursor", it)
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val clampedAttempt = attempt.coerceAtMost(6)
        val growth = 1L shl clampedAttempt
        return (BASE_RECONNECT_DELAY_MS * growth).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private enum class GraphqlProtocol {
        GRAPHQL_WS,
        GRAPHQL_TRANSPORT_WS;

        companion object {
            fun fromHeader(rawHeader: String): GraphqlProtocol {
                val normalized = rawHeader.trim().lowercase()
                return if (normalized.contains("transport")) GRAPHQL_TRANSPORT_WS else GRAPHQL_WS
            }
        }
    }

    private data class DesiredState(
        val shouldRunSocket: Boolean,
        val requiresForegroundService: Boolean,
        val listenForBundle: Boolean,
        val listenForManager: Boolean,
    ) {
        companion object {
            val NONE = DesiredState(
                shouldRunSocket = false,
                requiresForegroundService = false,
                listenForBundle = false,
                listenForManager = false
            )

            fun from(
                managerInterval: SearchForUpdatesBackgroundInterval,
                bundleInterval: SearchForUpdatesBackgroundInterval,
                mode: BundleUpdateDeliveryMode,
                isForeground: Boolean
            ): DesiredState {
                val listenForBundle = bundleInterval != SearchForUpdatesBackgroundInterval.NEVER
                val listenForManager = managerInterval != SearchForUpdatesBackgroundInterval.NEVER
                if (!listenForBundle && !listenForManager) {
                    return NONE
                }

                return when (mode) {
                    BundleUpdateDeliveryMode.POLLING_ONLY -> NONE
                    BundleUpdateDeliveryMode.AUTO -> DesiredState(
                        shouldRunSocket = isForeground,
                        requiresForegroundService = false,
                        listenForBundle = listenForBundle,
                        listenForManager = listenForManager
                    )
                    BundleUpdateDeliveryMode.WEBSOCKET_PREFERRED -> DesiredState(
                        shouldRunSocket = true,
                        requiresForegroundService = true,
                        listenForBundle = listenForBundle,
                        listenForManager = listenForManager
                    )
                }
            }
        }
    }

    private companion object {
        private const val TAG = "BundleUpdateWebSocket"
        private const val SUBSCRIPTION_ID = "bundle-refresh-jobs"
        private const val BASE_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val NETWORK_RETRY_DELAY_MS = 10_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 15_000L
        private const val SOCKET_IDLE_TIMEOUT_MS = 90_000L
        private const val MIN_TRIGGER_INTERVAL_MS = 60_000L
        private const val CURSOR_PREFS_FILE = "bundle_update_websocket"
        private const val KEY_LAST_COMPLETED_REFRESH_CURSOR = "last_completed_refresh_started_at"

        private val WEB_SOCKET_ENDPOINTS = listOf(
            "wss://revanced-external-bundles.brosssh.com/hasura/v1/graphql",
            "wss://revanced-external-bundles-dev.brosssh.com/hasura/v1/graphql"
        )

        private val REFRESH_SUBSCRIPTION_QUERY = """
            subscription BundleRefreshJobs {
              refresh_jobs(order_by: { started_at: desc }, limit: 1) {
                started_at
                status
              }
            }
        """.trimIndent()
    }

    private data class ForegroundListenState(
        val listenForBundle: Boolean,
        val listenForManager: Boolean
    )
}
