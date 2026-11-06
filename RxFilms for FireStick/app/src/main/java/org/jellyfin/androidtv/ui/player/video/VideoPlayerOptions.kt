package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.reloadCurrentEntry
import org.jellyfin.playback.core.model.PlayerTrackType
import org.koin.compose.koinInject

/** Audio or subtitle track picker; tracks are read when opened, since they change with every item. */
@Composable
fun TrackButton(
	playbackManager: PlaybackManager,
	type: PlayerTrackType,
) {
	val (icon, title) = when (type) {
		PlayerTrackType.AUDIO -> R.drawable.ic_select_audio to R.string.lbl_audio_track
		PlayerTrackType.SUBTITLE -> R.drawable.ic_select_subtitle to R.string.lbl_subtitle_track
	}
	OptionsPopoverButton(icon = icon, title = stringResource(title)) { close ->
		val tracks = remember { playbackManager.state.getTracks(type) }
		if (type == PlayerTrackType.SUBTITLE) {
			OptionItem(stringResource(R.string.lbl_none), selected = tracks.none { it.selected }) {
				playbackManager.state.selectTrack(type, null)
				close()
			}
		} else if (tracks.isEmpty()) {
			OptionItem(stringResource(R.string.lbl_none), selected = true) { close() }
		}
		for (track in tracks) {
			OptionItem(track.label, track.selected) {
				playbackManager.state.selectTrack(type, track.id)
				close()
			}
		}
	}
}

/** Same zoom modes and saved preference as the legacy player. */
@Composable
fun ZoomButton(zoom: MutableState<ZoomMode>) {
	val userPreferences = koinInject<UserPreferences>()
	OptionsPopoverButton(icon = R.drawable.ic_aspect_ratio, title = stringResource(R.string.lbl_zoom)) { close ->
		for (mode in ZoomMode.entries) {
			OptionItem(stringResource(mode.nameRes), zoom.value == mode) {
				zoom.value = mode
				userPreferences[UserPreferences.playerZoomMode] = mode
				close()
			}
		}
	}
}

/** Same quality list and saved max bitrate as the legacy player; the stream is re-requested at the new bitrate. */
@Composable
fun QualityButton(playbackManager: PlaybackManager) {
	val userPreferences = koinInject<UserPreferences>()
	val context = LocalContext.current
	val coroutineScope = rememberCoroutineScope()
	OptionsPopoverButton(icon = R.drawable.ic_select_quality, title = stringResource(R.string.lbl_quality_profile)) { close ->
		val current = userPreferences[UserPreferences.maxBitrate]
		for ((bitrate, label) in remember { getQualityProfiles(context) }) {
			OptionItem(label, bitrate == current) {
				close()
				if (bitrate == current) return@OptionItem
				userPreferences[UserPreferences.maxBitrate] = bitrate
				coroutineScope.launch { playbackManager.reloadCurrentEntry() }
			}
		}
	}
}

@Composable
private fun OptionsPopoverButton(
	icon: Int,
	title: String,
	content: @Composable (close: () -> Unit) -> Unit,
) = Box {
	var expanded by remember { mutableStateOf(false) }
	IconButton(onClick = { expanded = true }) {
		Icon(imageVector = ImageVector.vectorResource(icon), contentDescription = title)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
	) {
		Column(
			modifier = Modifier
				.width(320.dp)
				// Long lists (quality) scroll instead of growing past the screen.
				.heightIn(max = 420.dp)
				.verticalScroll(rememberScrollState())
				.padding(4.dp),
		) {
			Text(title, modifier = Modifier.padding(8.dp))
			if (expanded) content { expanded = false }
		}
	}
}

@Composable
private fun OptionItem(label: String, selected: Boolean, onClick: () -> Unit) = ListButton(
	onClick = onClick,
	headingContent = { Text(if (selected) "✓  $label" else label) },
)
