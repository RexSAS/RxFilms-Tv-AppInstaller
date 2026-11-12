package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.androidtv.ui.playback.overlay.SkipOverlayComposable
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.util.sdk.duration
import org.jellyfin.playback.core.PlaybackSeekCommand
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.mediasegment.mediaSegments
import org.jellyfin.playback.jellyfin.mediasegment.mediaSegmentsFlow
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.extensions.ticks
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
	zoom: MutableState<ZoomMode>? = null,
) {
	val visibilityState = rememberPlayerOverlayVisibility()
	var showPlaybackInfo by remember { mutableStateOf(false) }

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	val introEnd = rememberIntroSkip(playbackManager, entry)
	val togglePlayPause = rememberPlayPauseToggle(playbackManager)
	val skipIntro: (Duration) -> Unit = { end ->
		// Through the interceptor so a SyncPlay group skips together.
		if (playbackManager.commandInterceptor?.seek(end, PlaybackSeekCommand.POSITION) != true) playbackManager.state.seek(end)
	}

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
			onSelect = { introEnd?.let(skipIntro) ?: togglePlayPause() },
			header = {
				Column {
					VideoPlayerHeader(
						item = item,
					)
				}
			},
			controls = {
				VideoPlayerControls(
					playbackManager = playbackManager,
					onPlaybackInfoClick = { showPlaybackInfo = !showPlaybackInfo },
					zoom = zoom,
				)
			},
		)

		// Playback info overlay - positioned below header area, always visible when enabled
		if (showPlaybackInfo) {
			PlaybackInfoOverlay(
				playbackManager = playbackManager,
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(start = 48.dp, top = 100.dp)
			)
		}

		Box(modifier = Modifier.align(Alignment.BottomEnd)) {
			SkipOverlayComposable(visible = introEnd != null && !visibilityState.visible)
		}

		MediaToasts(mediaToastRegistry)
	}
}

/** The end of the intro playing now, while the "Skip" prompt should show (its first seconds), else null. */
@Composable
private fun rememberIntroSkip(playbackManager: PlaybackManager, entry: QueueEntry?): Duration? {
	val segments = entry?.run { mediaSegmentsFlow.collectAsState(mediaSegments) }?.value
	val introEnd by produceState<Duration?>(null, segments) {
		val intros = segments.orEmpty().filter {
			it.type == MediaSegmentType.INTRO && it.duration >= MediaSegmentRepository.AskToSkipMinDuration
		}
		while (true) {
			val position = playbackManager.state.positionInfo.active
			value = intros.firstOrNull { position >= it.startTicks.ticks && position < it.endTicks.ticks }?.endTicks?.ticks
			delay(POSITION_POLL)
		}
	}
	// Show the prompt for a while each time an intro starts, like the classic player.
	var hiddenEnd by remember { mutableStateOf<Duration?>(null) }
	LaunchedEffect(introEnd) {
		if (introEnd == null) return@LaunchedEffect
		delay(MediaSegmentRepository.AskToSkipAutoHideDuration)
		hiddenEnd = introEnd
	}
	return introEnd?.takeIf { it != hiddenEnd }
}

private val POSITION_POLL = 500.milliseconds
