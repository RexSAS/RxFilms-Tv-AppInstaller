package org.jellyfin.playback.jellyfin.syncplay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import timber.log.Timber
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/** UI maps these causes to localized messages; server errors and credentials are never display text. */
enum class SyncPlayFailure { REQUEST, DISCONNECTED, NO_CONNECTION, NOT_ALLOWED, PLAYBACK, GROUP_MISSING, ACCESS_DENIED }

data class SyncPlayStatus(
	val group: GroupInfoDto? = null,
	val queue: PlayQueueUpdate? = null,
	val joining: Boolean = false,
	val following: Boolean = false,
	val clockReady: Boolean = false,
	val error: SyncPlayFailure? = null,
)

/**
 * One coordinator per authenticated server session. All callers and [scope] must use one dispatcher.
 * Owns socket subscriptions even without a player. A disconnect requires an explicit fresh join;
 * HTTP mutations are never replayed automatically. Dispose before replacing the transport credentials.
 */
@Suppress("TooManyFunctions") // Coordinates independent membership, clock, queue, and player event contracts.
class SyncPlayCoordinator(
	private val scope: CoroutineScope,
	private val transport: SyncPlayTransport,
	private val wallMillis: () -> Long,
	private val monotonicMillis: () -> Long,
) : SyncPlayClient {
	private val session = SyncPlaySession()
	private val clock = SyncPlayClock()
	private val mutableState = MutableStateFlow(SyncPlayStatus())
	override val state: StateFlow<SyncPlayStatus> = mutableState.asStateFlow()
	private val mutableAlerts = MutableSharedFlow<SyncPlayAlert>(extraBufferCapacity = ALERT_BUFFER)
	override val alerts: SharedFlow<SyncPlayAlert> = mutableAlerts.asSharedFlow()
	private val membershipMutex = Mutex()
	private var player: SyncPlayPlayer? = null
	private var preparedItem: UUID? = null
	private var loadingItem: UUID? = null
	private var endedItem: UUID? = null
	private var messagesJob: Job? = null
	private var warmJob: Job? = null
	private var leaveJob: Job? = null
	private var connectionJob: Job? = null
	private var clockJob: Job? = null
	private var prepareJob: Job? = null
	private var actionJob: Job? = null
	private var playerEventsJob: Job? = null
	private var bufferingJob: Job? = null
	private var bufferingReportedItem: UUID? = null
	private val scheduler = SyncPlayCommandScheduler(scope, { clock.serverTime(monotonicMillis()) }, ::applyAction)

	override suspend fun groups(): List<GroupInfoDto> = transport.groups().also(::updateMembers)

	/** The server only names the members in group listings; keep ours current from any listing. */
	private fun updateMembers(groups: List<GroupInfoDto>) {
		val generation = session.state.value.generation
		val listed = groups.firstOrNull { it.groupId == state.value.group?.groupId } ?: return
		// Only the members: lastUpdatedAt still has to filter commands older than our join.
		if (session.updateGroup(generation, listed.groupId) { it.copy(participants = listed.participants.toList()) }) publish()
	}

	// The SDK only opens its socket while something subscribes; a short throwaway subscription starts the connection
	// while the user is still choosing, so Create/Join don't have to wait for it.
	override fun warmUp() {
		if (messagesJob?.isActive == true || warmJob?.isActive == true) return
		warmJob = scope.launch {
			try {
				withTimeoutOrNull(WARM_UP_MILLIS) { transport.events.collect { } }
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "SyncPlay: socket warm-up failed")
			}
		}
	}

	override suspend fun create(name: String): Boolean = joinOrCreate(null, name)
	override suspend fun join(id: UUID): Boolean = joinOrCreate(id, null)

	@Suppress("CyclomaticComplexMethod") // Every asynchronous boundary revalidates the membership generation.
	private suspend fun joinOrCreate(id: UUID?, name: String?): Boolean = membershipMutex.withLock {
		if (state.value.group != null || state.value.joining) return@withLock false
		// A leave still sent for an earlier failure must not land after this join and drop us again.
		leaveJob?.join()
		reset(stopPlayer = false)
		val generation = session.beginJoin(id)
		mutableState.value = SyncPlayStatus(joining = true, following = true)
		messagesJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				transport.events.collect { if (current(generation)) onEvent(generation, it) }
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "SyncPlay: event stream failed")
				if (current(generation)) failSession(SyncPlayFailure.DISCONNECTED)
			}
		}
		try {
			// SyncPlay updates only arrive over the socket, so without it there is nothing to join or create.
			if (withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { transport.connectionState.first { it is SocketApiState.Connected } } == null) {
				Timber.w("SyncPlay: no server socket after %d ms (%s)", REQUEST_TIMEOUT_MILLIS, transport.connectionState.value)
				if (current(generation)) failSession(SyncPlayFailure.NO_CONNECTION)
				return@withLock false
			}
			withTimeout(REQUEST_TIMEOUT_MILLIS) {
				if (id == null) {
					val created = transport.createGroup(requireNotNull(name))
					if (current(generation)) onJoined(generation, created)
				} else transport.joinGroup(id)
				state.first { !it.joining }
			}
			val groupId = state.value.group?.groupId
			if (!current(generation) || groupId == null) return@withLock false
			connectionJob = scope.launch { followConnection(generation, groupId) }
			true
		} catch (error: CancellationException) {
			if (current(generation)) failSession(SyncPlayFailure.REQUEST)
			throw error
		} catch (error: Exception) {
			Timber.w(error, "SyncPlay: join/create failed")
			val denied = error is InvalidStatusException && (error.status == 401 || error.status == 403)
			if (current(generation)) failSession(if (denied) SyncPlayFailure.NOT_ALLOWED else SyncPlayFailure.REQUEST)
			false
		}
	}

	/**
	 * The server keeps a session in its group across socket drops, so a short drop is bridged by joining the same
	 * group again once the socket is back: the official server then restores the session instead of adding it twice.
	 */
	private suspend fun followConnection(generation: Long, groupId: UUID) {
		while (current(generation)) {
			transport.connectionState.first { it !is SocketApiState.Connected }
			Timber.w("SyncPlay: socket lost, waiting up to %d ms to rejoin", RECONNECT_TIMEOUT_MILLIS)
			val back = withTimeoutOrNull(RECONNECT_TIMEOUT_MILLIS) { transport.connectionState.first { it is SocketApiState.Connected } }
			if (!current(generation)) return
			try {
				if (back == null) error("socket did not come back")
				withTimeout(REQUEST_TIMEOUT_MILLIS) { transport.joinGroup(groupId) }
				// Joining marks this session as loading again; say so if the item is already loaded.
				if (current(generation) && preparedItem != null) report(buffering = false)
				Timber.i("SyncPlay: rejoined %s after a socket drop", groupId)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "SyncPlay: could not rejoin after a socket drop")
				if (current(generation)) failSession(SyncPlayFailure.DISCONNECTED)
				return
			}
		}
	}

	private fun onJoined(generation: Long, group: GroupInfoDto) {
		if (!session.joined(generation, group)) return
		publish()
		if (clockJob?.isActive == true) return
		clockJob = scope.launch {
			var samples = 0
			while (current(generation)) {
				try {
					val sent = wallMillis()
					val monotonicSent = monotonicMillis()
					val response = withTimeout(REQUEST_TIMEOUT_MILLIS) { transport.time() }
					val received = wallMillis()
					val valid = clock.record(
						sent, response.requestReceptionTime.toSyncPlayInstant().toEpochMilli(),
						response.responseTransmissionTime.toSyncPlayInstant().toEpochMilli(), received,
						monotonicSent, monotonicMillis(),
					)
					if (!current(generation)) return@launch
					mutableState.value = state.value.copy(clockReady = clock.serverTime(monotonicMillis()) != null)
					if (valid) {
						transport.send(SyncPlayRequest.Ping(requireNotNull(clock.pingMillis)))
						reconcilePlayback()
					} else scheduler.cancel()
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					Timber.w(error, "SyncPlay: clock sync failed")
					// Retry clock acquisition, not playback mutations.
				}
				delay(if (++samples < INITIAL_SAMPLES) INITIAL_POLL_MILLIS else POLL_MILLIS)
			}
		}
	}

	@Suppress("CyclomaticComplexMethod") // Exhaustive dispatch over the SDK's sealed update protocol.
	private suspend fun onEvent(generation: Long, event: SyncPlayEvent) {
		Timber.i("SyncPlay: received %s", event)
		when (event) {
			is SyncPlayEvent.Command -> if (session.acceptCommand(generation, event.data)) {
				publish()
				reconcilePlayback()
			}
			is SyncPlayEvent.GroupUpdate -> {
				val update = event.data
				if (update is SyncPlayGroupJoinedUpdate) {
					onJoined(generation, update.data)
					return
				}
				if (update.groupId != state.value.group?.groupId && !state.value.joining) return
				when (update) {
					is SyncPlayPlayQueueUpdate -> if (session.updateQueue(generation, update.groupId, update.data)) {
						publish()
						// Leaving the player stops following so nobody waits; a new title brings everyone back.
						if (update.data.reason == PlayQueueUpdateReason.NEW_PLAYLIST && !state.value.following) {
							scope.launch { resume() }
						} else reconcilePlayback()
					}
					is SyncPlayGroupLeftUpdate, is SyncPlayNotInGroupUpdate -> reset(stopPlayer = false)
					is SyncPlayGroupDoesNotExistUpdate -> failSession(SyncPlayFailure.GROUP_MISSING)
					is SyncPlayLibraryAccessDeniedUpdate -> failSession(SyncPlayFailure.ACCESS_DENIED)
					is SyncPlayStateUpdate -> if (session.updateGroup(generation, update.groupId) { it.copy(state = update.data.state) }) publish()
					is SyncPlayUserJoinedUpdate -> {
						mutableAlerts.tryEmit(SyncPlayAlert.UserJoined(update.data))
						refreshParticipants(generation)
					}
					is SyncPlayUserLeftUpdate -> {
						mutableAlerts.tryEmit(SyncPlayAlert.UserLeft(update.data))
						refreshParticipants(generation)
					}
					else -> Unit
				}
			}
		}
	}

	private fun refreshParticipants(generation: Long) {
		scope.launch {
			try {
				val groups = transport.groups()
				if (current(generation)) updateMembers(groups)
			} catch (error: CancellationException) { throw error } catch (_: Exception) { /* Retain the last authoritative names. */ }
		}
	}

	fun attach(player: SyncPlayPlayer) {
		detach()
		this.player = player
		playerEventsJob = scope.launch {
			player.events.collect { event ->
				if (this@SyncPlayCoordinator.player != player || !state.value.following || preparedItem == null) return@collect
				when (event) {
					SyncPlayPlayerEvent.Buffering -> {
						val generation = session.state.value.generation
						val item = preparedItem
						if (bufferingReportedItem == item || bufferingJob?.isActive == true) return@collect
						bufferingJob = scope.launch {
							delay(BUFFERING_DELAY_MILLIS)
							if (current(generation) && preparedItem == item && report(buffering = true)) {
								bufferingReportedItem = item
							}
						}
					}
					SyncPlayPlayerEvent.Ready -> {
						bufferingJob?.cancel()
						if (bufferingReportedItem == preparedItem) {
							bufferingReportedItem = null
							report(buffering = false)
						}
					}
					SyncPlayPlayerEvent.Ended -> if (endedItem != preparedItem) {
						endedItem = preparedItem
						request(SyncPlayRequest.Next(requireNotNull(preparedItem)))
					}
					SyncPlayPlayerEvent.Failed -> playbackFailed()
				}
			}
		}
		reconcilePlayback()
	}

	fun detach() {
		cancelPlayback()
		playerEventsJob?.cancel()
		playerEventsJob = null
		player = null
	}

	@Suppress("CyclomaticComplexMethod") // Reconciles orthogonal clock, queue, occurrence, and preparation states.
	private fun reconcilePlayback() {
		Timber.i("SyncPlay: reconcile following=%s clockReady=%s player=%s", state.value.following, state.value.clockReady, player != null)
		if (!state.value.following || !state.value.clockReady) return
		val activePlayer = player ?: return
		val snapshot = session.state.value
		val item = snapshot.currentItem
		val command = snapshot.playbackCommand
		if (command?.command == SendCommandType.STOP) {
			cancelPlayback()
			scheduler.schedule(command)
			return
		}
		if (item == null) {
			cancelPlayback()
			activePlayer.stop()
			return
		}
		if (preparedItem != item.playlistItemId) {
			if (loadingItem == item.playlistItemId) return
			cancelPlayback()
			loadingItem = item.playlistItemId
			val generation = snapshot.generation
			prepareJob = scope.launch {
				try {
					val start = command?.positionTicks ?: snapshot.queue?.startPositionTicks ?: 0
					Timber.i("SyncPlay: preparing %s at %d", item.itemId, start)
					withTimeout(PREPARE_TIMEOUT_MILLIS) { activePlayer.prepare(item, start) }
					Timber.i("SyncPlay: prepared %s", item.itemId)
					if (!current(generation) || session.state.value.currentItem != item || player != activePlayer) return@launch
					preparedItem = item.playlistItemId
					loadingItem = null
					report(buffering = false)
					if (current(generation)) session.state.value.playbackCommand?.let(scheduler::schedule)
				} catch (error: CancellationException) { throw error } catch (error: Exception) {
					Timber.w(error, "SyncPlay: prepare failed")
					playbackFailed()
				}
			}
		} else if (command != null) scheduler.schedule(command)
	}

	private fun applyAction(action: SyncPlayAction) {
		Timber.i("SyncPlay: applying %s", action)
		val activePlayer = player ?: return
		val generation = session.state.value.generation
		actionJob?.cancel()
		actionJob = scope.launch {
			try {
				withTimeout(PREPARE_TIMEOUT_MILLIS) {
					when (action.command) {
						SendCommandType.STOP -> activePlayer.stop()
						SendCommandType.PAUSE -> {
							activePlayer.pause()
							val target = requireNotNull(action.positionTicks)
							if (abs(activePlayer.snapshot.positionTicks - target) > SEEK_TOLERANCE_TICKS) {
								activePlayer.seek(target)
							}
						}
						SendCommandType.SEEK -> {
							activePlayer.pause()
							activePlayer.seek(requireNotNull(action.positionTicks))
							if (current(generation)) report(buffering = false)
						}
						SendCommandType.UNPAUSE -> {
							val target = requireNotNull(action.positionTicks)
							if (abs(activePlayer.snapshot.positionTicks - target) > SEEK_TOLERANCE_TICKS) activePlayer.seek(target)
							if (current(generation)) activePlayer.unpause()
						}
					}
				}
			} catch (error: CancellationException) { throw error } catch (error: Exception) {
				Timber.w(error, "SyncPlay: %s failed", action)
				playbackFailed()
			}
		}
	}

	private suspend fun report(buffering: Boolean): Boolean {
		val item = preparedItem ?: return false
		val snapshot = player?.snapshot ?: return false
		val time = clock.serverTime(monotonicMillis())?.atZone(ZoneId.systemDefault())?.toLocalDateTime() ?: return false
		return request(if (buffering) SyncPlayRequest.Buffering(time, snapshot.positionTicks, snapshot.isPlaying, item)
		else SyncPlayRequest.Ready(time, snapshot.positionTicks, snapshot.isPlaying, item))
	}

	override suspend fun request(request: SyncPlayRequest): Boolean {
		if (state.value.group == null) return false
		val generation = session.state.value.generation
		return try {
			withTimeout(REQUEST_TIMEOUT_MILLIS) { transport.send(request) }
			current(generation)
		} catch (error: CancellationException) { throw error } catch (error: Exception) {
			Timber.w(error, "SyncPlay: request %s failed", request)
			if (current(generation)) mutableState.value = state.value.copy(error = SyncPlayFailure.REQUEST)
			false
		}
	}

	override suspend fun halt() {
		if (state.value.group == null) return
		cancelPlayback()
		player?.stop()
		mutableState.value = state.value.copy(following = false)
		request(SyncPlayRequest.IgnoreWait(true))
	}

	override suspend fun resume() {
		if (request(SyncPlayRequest.IgnoreWait(false))) {
			mutableState.value = state.value.copy(following = true, error = null)
			reconcilePlayback()
		}
	}

	override suspend fun leave() = membershipMutex.withLock {
		val wasJoined = state.value.group != null
		reset(stopPlayer = false)
		if (wasJoined) {
			try { withTimeout(REQUEST_TIMEOUT_MILLIS) { transport.leaveGroup() } }
			catch (error: CancellationException) { throw error }
			catch (_: Exception) { mutableState.value = state.value.copy(error = SyncPlayFailure.REQUEST) }
		}
	}

	/** Immediate local invalidation for logout/session replacement while retaining the attached app player. */
	override fun close() {
		reset(stopPlayer = true)
	}

	private fun playbackFailed() {
		cancelPlayback()
		player?.stop()
		mutableState.value = state.value.copy(following = false, error = SyncPlayFailure.PLAYBACK)
		scope.launch { request(SyncPlayRequest.IgnoreWait(true)) }
	}

	private fun failSession(error: SyncPlayFailure) {
		reset(stopPlayer = true)
		mutableState.value = state.value.copy(error = error)
		// The server keeps this session in its group until told otherwise, where the others see it and wait for it.
		leaveJob = scope.launch { leaveQuietly() }
	}

	override suspend fun leaveStale() = membershipMutex.withLock {
		if (state.value.group == null && !state.value.joining) leaveQuietly()
	}

	private suspend fun leaveQuietly() {
		try {
			withTimeout(REQUEST_TIMEOUT_MILLIS) { transport.leaveGroup() }
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "SyncPlay: best-effort leave failed")
		}
	}

	private fun cancelPlayback() {
		scheduler.cancel()
		prepareJob?.cancel()
		actionJob?.cancel()
		bufferingJob?.cancel()
		bufferingReportedItem = null
		preparedItem = null
		loadingItem = null
		endedItem = null
	}

	private fun reset(stopPlayer: Boolean) {
		cancelPlayback()
		if (stopPlayer) player?.stop()
		messagesJob?.cancel()
		connectionJob?.cancel()
		clockJob?.cancel()
		clock.reset()
		session.reset()
		mutableState.value = SyncPlayStatus()
	}

	private fun current(generation: Long) = session.state.value.generation == generation
	private fun publish() {
		val current = session.state.value
		mutableState.value = state.value.copy(group = current.group, queue = current.queue, joining = current.joining)
	}

	private companion object {
		const val REQUEST_TIMEOUT_MILLIS = 10_000L
		const val RECONNECT_TIMEOUT_MILLIS = 30_000L
		const val WARM_UP_MILLIS = 60_000L
		const val ALERT_BUFFER = 8
		const val PREPARE_TIMEOUT_MILLIS = 30_000L
		const val BUFFERING_DELAY_MILLIS = 3000L
		const val INITIAL_SAMPLES = 3
		const val INITIAL_POLL_MILLIS = 1000L
		const val POLL_MILLIS = 60_000L
		const val SEEK_TOLERANCE_TICKS = 2_500_000L
	}
}
