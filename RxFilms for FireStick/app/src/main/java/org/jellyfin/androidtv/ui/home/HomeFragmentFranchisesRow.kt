package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.dp
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.studiosApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/**
 * [tags] are Jellyfin studio names; [logoPadding] shrinks for logos that need to be drawn larger.
 * A genre tile has a [genreId] and no [logo]: its name is drawn instead.
 */
data class Franchise(
	val name: String,
	val tags: List<String>,
	val colors: IntArray,
	val logo: String?,
	val genreId: UUID? = null,
	val invert: Boolean = false,
	val logoPadding: Int = 24,
)

/**
 * Logo cards per franchise (port of the RxFilms web script), or with [genres] one card per server genre.
 * Selecting one opens a row with its titles, newest first, right below; selecting it again closes it.
 */
class HomeFragmentFranchisesRow(
	private val scope: LifecycleCoroutineScope,
	private val api: ApiClient,
	private val genres: Boolean = false,
) : HomeFragmentRow, OnItemViewClickedListener {
	private var context: Context? = null
	private var cardPresenter: CardPresenter? = null
	private var rowsAdapter: MutableObjectAdapter<Row>? = null
	private var franchiseRow: ListRow? = null
	private var openRow: ListRow? = null
	private var openFranchise: Franchise? = null

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		this.context = context
		this.cardPresenter = cardPresenter
		this.rowsAdapter = rowsAdapter
		val adapter = MutableObjectAdapter<Franchise>(FranchisePresenter())
		val title = if (genres) R.string.home_section_genres else R.string.home_section_franchises
		val row = ListRow(HeaderItem(context.getString(title)), adapter)
		franchiseRow = row
		rowsAdapter.add(row)
		if (!genres) FRANCHISES.forEach(adapter::add)
		else scope.launch {
			val items = withContext(Dispatchers.IO) {
				runCatching { api.genresApi.getGenres(includeItemTypes = TYPES).content.items }.getOrDefault(emptyList())
			}
			if (items.isEmpty()) rowsAdapter.remove(row)
			items.forEachIndexed { i, genre ->
				adapter.add(Franchise(genre.name.orEmpty(), emptyList(), FRANCHISES[i % FRANCHISES.size].colors, null, genre.id))
			}
		}
	}

	override fun onItemClicked(itemViewHolder: Presenter.ViewHolder?, item: Any?, rowViewHolder: RowPresenter.ViewHolder?, row: Row?) {
		if (item !is Franchise || row !== franchiseRow) return
		val rowsAdapter = rowsAdapter ?: return
		openRow?.let(rowsAdapter::remove)
		openRow = null
		if (openFranchise == item) {
			openFranchise = null
			return
		}
		openFranchise = item

		scope.launch {
			val studioIds = if (item.genreId != null) emptyList() else withContext(Dispatchers.IO) {
				// The SDK joins lists with commas but the server splits studio *names* on "|", so filter by id instead.
				item.tags.mapNotNull { tag -> runCatching { api.studiosApi.getStudio(tag).content.id }.getOrNull() }
			}
			val context = context ?: return@launch
			if (openFranchise != item || (studioIds.isEmpty() && item.genreId == null)) return@launch

			val query = GetItemsRequest(
				studioIds = studioIds.ifEmpty { null },
				genreIds = item.genreId?.let(::setOf),
				includeItemTypes = TYPES,
				recursive = true,
				sortBy = setOf(ItemSortBy.PREMIERE_DATE),
				sortOrder = setOf(SortOrder.DESCENDING),
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
			)
			val adapter = ItemRowAdapter(context, query, ITEM_CHUNK_SIZE, false, cardPresenter, rowsAdapter)
			val titlesRow = ListRow(HeaderItem(item.name), adapter)
			adapter.setRow(titlesRow)
			rowsAdapter.add(rowsAdapter.indexOfFirst { it === franchiseRow } + 1, titlesRow)
			openRow = titlesRow
			adapter.Retrieve()
		}
	}

	private class FranchisePresenter : Presenter() {
		override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
			val background = GradientDrawable().apply { cornerRadius = 8.dp(parent.context).toFloat() }
			val logo = AsyncImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
			val name = TextView(parent.context).apply {
				gravity = Gravity.CENTER
				setTextColor(Color.WHITE)
				textSize = 20f
				val padding = 12.dp(context)
				setPadding(padding, padding, padding, padding)
			}
			val card = FrameLayout(parent.context).apply {
				layoutParams = ViewGroup.LayoutParams(CARD_WIDTH.dp(context), CARD_HEIGHT.dp(context))
				isFocusable = true
				isFocusableInTouchMode = true
				this.background = background
				addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
				addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
				setOnFocusChangeListener { _, focused -> background.setStroke(if (focused) 3.dp(context) else 0, Color.WHITE) }
			}
			return ViewHolder(card)
		}

		override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
			val franchise = item as? Franchise ?: return
			val card = viewHolder.view as FrameLayout
			val logo = card.getChildAt(0) as AsyncImageView
			(card.background as GradientDrawable).apply {
				orientation = GradientDrawable.Orientation.TL_BR
				colors = franchise.colors
			}
			franchise.logoPadding.dp(card.context).let { logo.setPadding(it, it, it, it) }
			if (franchise.invert) logo.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) else logo.clearColorFilter()
			logo.contentDescription = franchise.name
			(card.getChildAt(1) as TextView).text = if (franchise.logo == null) franchise.name else null
			logo.load(url = franchise.logo)
		}

		override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
	}

	private companion object {
		const val ITEM_CHUNK_SIZE = 50
		val TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		const val CARD_WIDTH = 220
		const val CARD_HEIGHT = 124

		fun colors(vararg hex: String) = hex.map(Color::parseColor).toIntArray()

		// Copied from the RxFilms web script; padding 14 = "big", 8 = "morebig".
		val FRANCHISES = listOf(
			Franchise("Marvel", listOf("Marvel Entertainment", "Marvel Studios"), colors("#1a0a0a", "#7a1f1f", "#0a0a0a"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/mIkZDuulwMPzESbzF9lg3rD8CcO.png"),
			Franchise("20th Century Studios", listOf("20th Century Studios"), colors("#0a0a1a", "#8a6b00", "#0d0906"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/h0rjX5vjW5r8yEnUBStFarjcLT4.png", logoPadding = 14),
			Franchise("DC Universe", listOf("DC Films", "DC"), colors("#071226", "#0f4f8b", "#13040b"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/2Tc1P3Ac8M479naPp1kYT3izLS5.png", logoPadding = 14),
			Franchise("Disney", listOf("Walt Disney Productions", "Walt Disney Pictures", "Disney Television Animation"),
				colors("#071426", "#0f5f9b", "#07101c"), "https://i.imgur.com/cPpeEuL.png", logoPadding = 8),
			Franchise("Columbia Pictures", listOf("Columbia Pictures"), colors("#1b0d10", "#7b1f2e", "#090909"),
				"https://i.imgur.com/9XhT64p.png", invert = true),
			Franchise("Warner Bros. Pictures", listOf("Warner Bros. Pictures"), colors("#0a1018", "#c28a1f", "#0a0a0a"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/ky0xOc5OrhzkZ1N6KyUxacfQsCk.png", logoPadding = 14),
			Franchise("Universal", listOf("Universal Pictures"), colors("#07121f", "#2f4fa3", "#04070f"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/8lvHyhjr8oUKOOy2dKXoALWKdp0.png", logoPadding = 14),
			Franchise("Dreamworks", listOf("DreamWorks Animation", "DreamWorks"), colors("#07141c", "#0f7a8a", "#020b12"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/kP7t6RwGz2AvvTkvnI1uteEwHet.png", logoPadding = 14),
			Franchise("Paramount", listOf("Paramount Pictures", "Paramount"), colors("#0a0f1f", "#4f5d8d", "#070b16"),
				"https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/fycMZt242LVjagMByZOLUGbCvv3.png", logoPadding = 14),
		)
	}
}
