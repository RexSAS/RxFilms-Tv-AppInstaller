package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.seerr.SeerrCardPresenter
import org.jellyfin.androidtv.ui.seerr.SeerrTitle
import timber.log.Timber

/** Seerr titles; selecting one opens it when available, otherwise searches for it (handled in HomeRowsFragment). */
class HomeFragmentSeerrRow(
	private val scope: LifecycleCoroutineScope,
	private val title: String,
	private val load: suspend () -> List<SeerrTitle>,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val adapter = MutableObjectAdapter<SeerrTitle>(SeerrCardPresenter())
		val row = ListRow(HeaderItem(title), adapter)
		rowsAdapter.add(row)

		scope.launch {
			val items = runCatching { load() }.onFailure { Timber.w(it, "Unable to load $title") }.getOrDefault(emptyList())
			if (items.isEmpty()) rowsAdapter.remove(row)
			else adapter.replaceAll(items)
		}
	}
}
