package org.jellyfin.androidtv.ui.search

import android.app.AlertDialog
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import android.widget.Toast
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.seerr.Seerr
import org.jellyfin.androidtv.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.ui.seerr.SeerrTitle
import timber.log.Timber

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
	private val navigationRepository: NavigationRepository,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())

	fun showResults(searchResultGroups: Collection<SearchResultGroup>, seerrResults: List<SeerrTitle> = emptyList()) {
		rowsAdapter.clear()
		val adapters = mutableListOf<ItemRowAdapter>()
		for ((labelRes, baseItems) in searchResultGroups) {
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				rowsAdapter,
				QueryType.Search
			).apply {
				setRow(ListRow(HeaderItem(context.getString(labelRes)), this))
			}
			adapters.add(adapter)
		}
		for (adapter in adapters) adapter.Retrieve()

		if (seerrResults.isNotEmpty()) {
			val adapter = MutableObjectAdapter<SeerrTitle>(SeerrCardPresenter()).apply { replaceAll(seerrResults) }
			rowsAdapter.add(ListRow(HeaderItem(context.getString(R.string.seerr_search_results)), adapter))
		}
	}

	private fun confirmRequest(title: SeerrTitle) {
		AlertDialog.Builder(context)
			.setTitle(context.getString(R.string.seerr_request_title, title.title))
			.setMessage(R.string.seerr_request_message)
			.setPositiveButton(R.string.seerr_request_confirm) { _, _ ->
				ProcessLifecycleOwner.get().lifecycleScope.launch {
					val result = runCatching { Seerr.request(title) }.onFailure { Timber.w(it, "Seerr request failed") }
					val message = if (result.isSuccess) R.string.seerr_request_done else R.string.seerr_request_failed
					Toast.makeText(context, message, Toast.LENGTH_LONG).show()
				}
			}
			.setNegativeButton(R.string.btn_cancel, null)
			.show()
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item is SeerrTitle) {
			val availableId = item.availableItemId
			if (availableId != null) navigationRepository.navigate(Destinations.itemDetails(availableId))
			else confirmRequest(item)
			return@OnItemViewClickedListener
		}
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		row as ListRow
		val adapter = row.adapter as ItemRowAdapter
		itemLauncher.launch(item as BaseRowItem?, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val baseItem = (item as? BaseRowItem)?.baseItem
		if (baseItem != null) {
			backgroundService.setBackground(baseItem)
		} else {
			backgroundService.clearBackgrounds()
		}
	}
}
