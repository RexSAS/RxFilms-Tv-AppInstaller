package org.jellyfin.playback.jellyfin.syncplay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jellyfin.sdk.model.api.GroupInfoDto
import java.util.UUID

/** One-shot group membership notices; the name is the server's display name of the other member. */
sealed interface SyncPlayAlert {
	data class UserJoined(val name: String) : SyncPlayAlert
	data class UserLeft(val name: String) : SyncPlayAlert
}

/** Membership operations exposed to application UI. */
interface SyncPlayClient {
	val state: StateFlow<SyncPlayStatus>
	val alerts: Flow<SyncPlayAlert> get() = emptyFlow()

	suspend fun groups(): List<GroupInfoDto>
	suspend fun create(name: String): Boolean
	suspend fun join(id: UUID): Boolean
	suspend fun leave()
	suspend fun halt()
	suspend fun resume()
	suspend fun request(request: SyncPlayRequest): Boolean
	fun close()
}
