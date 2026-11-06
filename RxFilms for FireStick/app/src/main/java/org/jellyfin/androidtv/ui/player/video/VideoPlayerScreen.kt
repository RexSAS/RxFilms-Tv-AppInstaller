package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.player.base.PlayerSubtitles
import org.jellyfin.androidtv.ui.player.base.PlayerSurface
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.video.toast.rememberPlaybackManagerMediaToastEmitter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private const val DefaultVideoAspectRatio = 16f / 9f

/** Fit letterboxes, stretch fills the screen, auto crop covers it and cuts the overflowing edges. */
private fun Modifier.zoom(mode: ZoomMode, aspectRatio: Float, landscape: Boolean) = when (mode) {
	ZoomMode.FIT -> aspectRatio(aspectRatio, landscape).fillMaxSize()
	ZoomMode.STRETCH -> fillMaxSize()
	ZoomMode.AUTO_CROP -> layout { measurable, constraints ->
		val width = constraints.maxWidth
		val height = constraints.maxHeight
		val (coverWidth, coverHeight) =
			if (width.toFloat() / height > aspectRatio) width to (width / aspectRatio).roundToInt()
			else (height * aspectRatio).roundToInt() to height
		val placeable = measurable.measure(Constraints.fixed(coverWidth, coverHeight))
		layout(width, height) { placeable.place((width - coverWidth) / 2, (height - coverHeight) / 2) }
	}
}

@Composable
fun VideoPlayerScreen() {
	val playbackManager = koinInject<PlaybackManager>()

	val backgroundService = koinInject<BackgroundService>()
	LaunchedEffect(backgroundService) {
		backgroundService.clearBackgrounds()
	}

	val playing by remember {
		playbackManager.state.playState.map { it == PlayState.PLAYING }
	}.collectAsState(false)
	ScreensaverLock(
		enabled = playing,
	)

	val videoSize by playbackManager.state.videoSize.collectAsState()
	val aspectRatio = videoSize.aspectRatio.takeIf { !it.isNaN() && it > 0f } ?: DefaultVideoAspectRatio
	val userPreferences = koinInject<UserPreferences>()
	val zoom = remember { mutableStateOf(userPreferences[UserPreferences.playerZoomMode]) }

	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	rememberPlaybackManagerMediaToastEmitter(playbackManager, mediaToastRegistry)

	Box(
		modifier = Modifier
			.background(Color.Black)
			.fillMaxSize()
	) {
		PlayerSurface(
			playbackManager = playbackManager,
			modifier = Modifier
				.zoom(zoom.value, aspectRatio, videoSize.height < videoSize.width)
				.align(Alignment.Center)
		)

		VideoPlayerOverlay(
			playbackManager = playbackManager,
			mediaToastRegistry = mediaToastRegistry,
			zoom = zoom,
		)

		PlayerSubtitles(
			playbackManager = playbackManager,
			modifier = Modifier
				.aspectRatio(aspectRatio, videoSize.height < videoSize.width)
				.fillMaxSize()
				.align(Alignment.Center)
		)
	}
}
