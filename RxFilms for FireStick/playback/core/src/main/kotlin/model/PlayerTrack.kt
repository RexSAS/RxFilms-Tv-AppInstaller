package org.jellyfin.playback.core.model

enum class PlayerTrackType { AUDIO, SUBTITLE }

/** A selectable audio or subtitle track of the current item; [id] is only meaningful to the backend. */
data class PlayerTrack(
	val id: String,
	val label: String,
	val selected: Boolean,
)
