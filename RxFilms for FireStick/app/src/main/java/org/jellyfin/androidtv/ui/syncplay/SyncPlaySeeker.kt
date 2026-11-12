package org.jellyfin.androidtv.ui.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.playback.jellyfin.syncplay.SyncPlayClient
import org.jellyfin.playback.jellyfin.syncplay.SyncPlayRequest
import kotlin.time.Duration

/**
 * Group seeks from this TV (buttons, seekbar, intro skip, remote). Each group Seek makes every member pause and
 * report ready again, so a held or repeated key must not send one per press: the local player moves at once, which
 * also makes the next press start from the new position, and the group gets the final position once presses stop.
 * Call on the main thread.
 */
class SyncPlaySeeker(
	private val syncPlay: SyncPlayClient,
	private val localSeek: (Duration) -> Unit,
	private val scope: CoroutineScope,
) {
	private var send: Job? = null

	fun seek(target: Duration) {
		val position = target.coerceAtLeast(Duration.ZERO)
		localSeek(position)
		send?.cancel()
		send = scope.launch {
			delay(SETTLE_MILLIS)
			syncPlay.request(SyncPlayRequest.Seek(position.inWholeMilliseconds * TICKS_PER_MILLISECOND))
		}
	}

	private companion object {
		const val SETTLE_MILLIS = 500L
		const val TICKS_PER_MILLISECOND = 10_000L
	}
}
