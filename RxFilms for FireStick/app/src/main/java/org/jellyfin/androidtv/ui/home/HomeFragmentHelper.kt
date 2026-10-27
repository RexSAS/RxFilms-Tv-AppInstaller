package org.jellyfin.androidtv.ui.home

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest

class HomeFragmentHelper(
	private val context: Context,
	private val userRepository: UserRepository,
	private val api: ApiClient,
) {
	fun loadRecentlyAdded(userViews: Collection<BaseItemDto>): HomeFragmentRow {
		return HomeFragmentLatestRow(userRepository, userViews)
	}

	fun loadResume(title: String, includeMediaTypes: Collection<MediaType>): HomeFragmentRow {
		val query = GetResumeItemsRequest(
			limit = ITEM_LIMIT_RESUME,
			fields = ItemRepository.browseFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			mediaTypes = includeMediaTypes,
			excludeItemTypes = setOf(BaseItemKind.AUDIO_BOOK),
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(title, query, 0, false, true, arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)))
	}

	fun loadResumeVideo(): HomeFragmentRow {
		return loadResume(context.getString(R.string.lbl_continue_watching), listOf(MediaType.VIDEO))
	}

	fun loadResumeAudio(): HomeFragmentRow {
		return loadResume(context.getString(R.string.continue_listening), listOf(MediaType.AUDIO))
	}

	fun loadLatestLiveTvRecordings(): HomeFragmentRow {
		val query = GetRecordingsRequest(
			fields = ItemRepository.itemFields,
			enableImages = true,
			limit = ITEM_LIMIT_RECORDINGS
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_recordings), query))
	}

	fun loadNextUp(): HomeFragmentRow {
		val query = GetNextUpRequest(
			imageTypeLimit = 1,
			limit = ITEM_LIMIT_NEXT_UP,
			enableResumable = false,
			fields = ItemRepository.browseFields
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_next_up), query, arrayOf(ChangeTriggerType.TvPlayback)))
	}

	fun loadOnNow(): HomeFragmentRow {
		val query = GetRecommendedProgramsRequest(
			isAiring = true,
			fields = ItemRepository.itemFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = ITEM_LIMIT_ON_NOW
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_on_now), query))
	}

	/** Similar titles for the last watched movie and the last watched series. Call from a background thread. */
	suspend fun loadBecauseYouWatched(): List<HomeFragmentRow> = listOf(
		BaseItemKind.MOVIE to QueryType.SimilarMovies,
		BaseItemKind.EPISODE to QueryType.SimilarSeries,
	).mapNotNull { (kind, queryType) ->
		val watched = api.itemsApi.getItems(
			includeItemTypes = setOf(kind),
			isPlayed = true,
			sortBy = setOf(ItemSortBy.DATE_PLAYED),
			sortOrder = setOf(SortOrder.DESCENDING),
			recursive = true,
			limit = 1,
		).content.items.firstOrNull() ?: return@mapNotNull null
		val itemId = if (kind == BaseItemKind.EPISODE) watched.seriesId ?: return@mapNotNull null else watched.id
		val name = if (kind == BaseItemKind.EPISODE) watched.seriesName ?: watched.name else watched.name
		val query = GetSimilarItemsRequest(itemId = itemId, fields = ItemRepository.browseFields, limit = ITEM_LIMIT_SIMILAR)
		HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.because_you_watched, name), query, queryType))
	}

	/** One row each for a few random genres, so the home screen changes between visits. Call from a background thread. */
	suspend fun loadGenres(): List<HomeFragmentRow> {
		val types = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		val genres = api.genresApi.getGenres(includeItemTypes = types).content.items
		return genres.shuffled().take(GENRE_ROW_COUNT).map { genre ->
			val query = GetItemsRequest(
				genreIds = setOf(genre.id),
				includeItemTypes = types,
				recursive = true,
				sortBy = setOf(ItemSortBy.RANDOM),
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				limit = ITEM_LIMIT_GENRE,
			)
			HomeFragmentBrowseRowDefRow(BrowseRowDef(genre.name, query, ITEM_LIMIT_GENRE))
		}
	}

	companion object {
		// Maximum amount of items loaded for a row
		private const val ITEM_LIMIT_RESUME = 50
		private const val ITEM_LIMIT_RECORDINGS = 40
		private const val ITEM_LIMIT_NEXT_UP = 50
		private const val ITEM_LIMIT_ON_NOW = 20
		private const val ITEM_LIMIT_SIMILAR = 20
		private const val ITEM_LIMIT_GENRE = 30
		private const val GENRE_ROW_COUNT = 3
	}
}
