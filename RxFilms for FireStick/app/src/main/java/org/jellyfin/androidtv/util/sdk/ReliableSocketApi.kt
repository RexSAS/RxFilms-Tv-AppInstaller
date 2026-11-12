package org.jellyfin.androidtv.util.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.api.sockets.SocketConnectionState
import org.jellyfin.sdk.model.api.ActivityLogEntryMessage
import org.jellyfin.sdk.model.api.ActivityLogEntryStartMessage
import org.jellyfin.sdk.model.api.ActivityLogEntryStopMessage
import org.jellyfin.sdk.model.api.ForceKeepAliveMessage
import org.jellyfin.sdk.model.api.InboundKeepAliveMessage
import org.jellyfin.sdk.model.api.InboundWebSocketMessage
import org.jellyfin.sdk.model.api.OutboundKeepAliveMessage
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.jellyfin.sdk.model.api.ScheduledTasksInfoMessage
import org.jellyfin.sdk.model.api.ScheduledTasksInfoStartMessage
import org.jellyfin.sdk.model.api.ScheduledTasksInfoStopMessage
import org.jellyfin.sdk.model.api.SessionsMessage
import org.jellyfin.sdk.model.api.SessionsStartMessage
import org.jellyfin.sdk.model.api.SessionsStopMessage
import org.jellyfin.sdk.model.socket.PeriodicListenerPeriod
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Server feeds that only send messages between a start and a stop message (the SDK's list is internal). */
private class FeedType(
	val messageType: KClass<out OutboundWebSocketMessage>,
	val start: (period: String) -> InboundWebSocketMessage,
	val stop: () -> InboundWebSocketMessage,
)

private val FEED_TYPES = listOf(
	FeedType(SessionsMessage::class, { SessionsStartMessage(it) }, { SessionsStopMessage() }),
	FeedType(ActivityLogEntryMessage::class, { ActivityLogEntryStartMessage(it) }, { ActivityLogEntryStopMessage() }),
	FeedType(ScheduledTasksInfoMessage::class, { ScheduledTasksInfoStartMessage(it) }, { ScheduledTasksInfoStopMessage() }),
)

/**
 * Replacement for the SDK 1.8.12 DefaultSocketApi, which can leave the app without a WebSocket until it restarts:
 * - its subscription counter is updated from several threads without synchronization and drifts (seen at -1);
 *   reaching 0 closes the socket while subscribers remain, and nothing reopens it (fixed upstream in SDK 1.9.1,
 *   jellyfin-sdk-kotlin 0c9551d8c, which needs the Jellyfin 12 API this app doesn't use yet);
 * - it only reconnects after an error, so a clean close by the server (restart, lost keep-alive) is never recovered.
 * The server ends the session, and with it any SyncPlay membership, when a client's last socket closes, so the
 * socket must stay up for as long as anything listens.
 */
class ReliableSocketApi(
	private val api: ApiClient,
	connectionFactory: SocketConnectionFactory,
	private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : SocketApi {
	private data class Credentials(
		val url: String,
		val clientName: String,
		val clientVersion: String,
		val deviceId: String,
		val deviceName: String,
		val accessToken: String,
	)

	private val connection = connectionFactory.create(api.httpClientOptions, scope)
	private val subscriptions = AtomicInteger(0)
	internal val activeSubscriptions: Int get() = subscriptions.get()
	private val typeUsage = mutableMapOf<FeedType, Int>()
	private val reconnectMutex = Mutex()
	private val attempts = AtomicInteger(0)

	@Volatile private var credentials: Credentials? = null
	@Volatile private var keepAlive: Job? = null
	@Volatile private var retry: Job? = null

	// Set while this class closes the socket on purpose, so that close isn't answered with a reconnect.
	@Volatile private var closing = false

	override val state: StateFlow<SocketApiState> = connection.state
		.map { state ->
			when (state) {
				SocketConnectionState.Connecting -> SocketApiState.Connecting
				is SocketConnectionState.Disconnected -> SocketApiState.Disconnected(state.error)
				is SocketConnectionState.Message -> SocketApiState.Connected
			}
		}
		.distinctUntilChanged()
		.stateIn(scope, SharingStarted.Eagerly, SocketApiState.Disconnected())

	private val messages: SharedFlow<OutboundWebSocketMessage> = connection.state
		.onEach { state ->
			if (state is SocketConnectionState.Disconnected) {
				keepAlive?.cancel()
				scheduleReconnect(state.error)
			}
		}
		.filterIsInstance<SocketConnectionState.Message>()
		.mapNotNull { state ->
			try {
				ApiSerializer.decodeSocketMessage(state.message)
			} catch (error: Exception) {
				Timber.w(error, "Ignoring an unreadable WebSocket message")
				null
			}
		}
		.filter { message ->
			when (message) {
				is ForceKeepAliveMessage -> {
					startKeepAlive(message.data.seconds)
					false
				}

				is OutboundKeepAliveMessage -> false
				else -> true
			}
		}
		.onStart { reconnect() }
		.onCompletion {
			// The last subscriber left more than the stop timeout ago.
			closing = true
			retry?.cancel()
			keepAlive?.cancel()
			connection.disconnect()
		}
		.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT.inWholeMilliseconds))

	private fun scheduleReconnect(error: Throwable?) {
		if (closing || subscriptions.get() <= 0 || credentials == null) return
		synchronized(this) {
			if (retry?.isActive == true || reconnectMutex.isLocked) return
			val wait = backoff(attempts.getAndIncrement())
			Timber.w(error, "WebSocket closed with %d subscribers, reconnecting in %s", subscriptions.get(), wait)
			retry = scope.launch {
				delay(wait)
				// Another path may have reconnected meanwhile.
				if (!closing && subscriptions.get() > 0 && connection.state.value is SocketConnectionState.Disconnected) reconnect()
			}
		}
	}

	private suspend fun reconnect(): Unit = reconnectMutex.withLock {
		val newCredentials = createCredentials()
		credentials = newCredentials
		keepAlive?.cancel()

		if (newCredentials == null) {
			closing = true
			connection.disconnect()
			return@withLock
		}

		closing = false
		val connected = connection.connect(
			url = newCredentials.url,
			clientName = newCredentials.clientName,
			clientVersion = newCredentials.clientVersion,
			deviceId = newCredentials.deviceId,
			deviceName = newCredentials.deviceName,
			accessToken = newCredentials.accessToken,
		)
		if (connected) {
			attempts.set(0)
			Timber.i("WebSocket connected")
			val types = synchronized(typeUsage) { typeUsage.filterValues { it > 0 }.keys.toList() }
			for (type in types) publish(type.start(SUBSCRIPTION_PERIOD.toString()))
		}
	}

	private fun startKeepAlive(lostTimeout: Duration) {
		// The server drops sockets that stay silent for [lostTimeout]; talk at twice that rate.
		val interval = lostTimeout / 2
		keepAlive?.cancel()
		keepAlive = scope.launch {
			while (true) {
				publish(InboundKeepAliveMessage())
				delay(interval)
			}
		}
	}

	private suspend fun publish(message: InboundWebSocketMessage) {
		connection.send(ApiSerializer.encodeSocketMessage(message))
	}

	private fun createCredentials(): Credentials? {
		val accessToken = api.accessToken
		if (api.baseUrl == null || accessToken == null) return null
		return Credentials(
			url = api.createUrl(SOCKET_URL),
			clientName = api.clientInfo.name,
			clientVersion = api.clientInfo.version,
			deviceId = api.deviceInfo.id,
			deviceName = api.deviceInfo.name,
			accessToken = accessToken,
		)
	}

	/** Called by [ReliableApiClient.update]: a new server, user or token needs a new socket. */
	fun notifyApiClientUpdate() {
		if (subscriptions.get() <= 0 || createCredentials() == credentials) return
		attempts.set(0)
		scope.launch { reconnect() }
	}

	private fun begin(types: Set<FeedType>) {
		subscriptions.incrementAndGet()
		val connected = connection.state.value is SocketConnectionState.Message
		for (type in types) {
			val first = synchronized(typeUsage) {
				val usage = typeUsage.getOrDefault(type, 0)
				typeUsage[type] = usage + 1
				usage == 0
			}
			if (first && connected) scope.launch { publish(type.start(SUBSCRIPTION_PERIOD.toString())) }
		}
	}

	private suspend fun end(types: Set<FeedType>) {
		subscriptions.decrementAndGet()
		// No disconnect here: [messages] stops sharing, and closes the socket, once nobody collects it.
		for (type in types) {
			val last = synchronized(typeUsage) {
				val usage = (typeUsage.getOrDefault(type, 0) - 1).coerceAtLeast(0)
				typeUsage[type] = usage
				usage == 0
			}
			if (last && connection.state.value is SocketConnectionState.Message) publish(type.stop())
		}
	}

	private fun <T> subscription(types: Set<FeedType>, source: Flow<T>): Flow<T> = flow {
		begin(types)
		try {
			emitAll(source)
		} finally {
			// Exactly one decrement per collection, whatever thread or reason ends it.
			withContext(NonCancellable) { end(types) }
		}
	}

	override fun subscribeAll(): Flow<OutboundWebSocketMessage> = subscription(FEED_TYPES.toSet(), messages)

	override fun <T : OutboundWebSocketMessage> subscribe(messageType: KClass<T>): Flow<T> {
		val type = FEED_TYPES.firstOrNull { it.messageType == messageType }
		return subscription(if (type == null) emptySet() else setOf(type), messages.filterIsInstance(messageType))
	}

	private companion object {
		const val SOCKET_URL = "/socket"
		val SUBSCRIPTION_PERIOD = PeriodicListenerPeriod(Duration.ZERO, 1.seconds)
		val STOP_TIMEOUT = 5.seconds
		val MAX_BACKOFF = 30.seconds

		fun backoff(attempt: Int): Duration = (1.seconds * (1 shl attempt.coerceIn(0, 5))).coerceAtMost(MAX_BACKOFF)
	}
}
