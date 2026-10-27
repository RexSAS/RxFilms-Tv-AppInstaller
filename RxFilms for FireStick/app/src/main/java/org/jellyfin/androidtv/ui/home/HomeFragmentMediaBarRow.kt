package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.util.Locale

private const val ROTATE_MILLIS = 12_000L
private const val CROSSFADE_MILLIS = 1200
private const val HEIGHT_FRACTION = 0.88f

/** Rotating banner of random titles at the top of home, like the Media Bar web plugin. */
class MediaBar(val items: List<BaseItemDto>) {
	val index = mutableIntStateOf(0)

	// Selected row, not focus: the info stays visible while focus is up in the toolbar.
	val rowSelected = mutableStateOf(true)
	val current get() = items[index.intValue]

	fun move(step: Int) {
		index.intValue = (index.intValue + step).mod(items.size)
	}
}

/** Separate row type so the banner gets a presenter without the focus zoom that the poster rows use. */
class MediaBarRow(adapter: MutableObjectAdapter<MediaBar>) : ListRow(adapter)

class HomeFragmentMediaBarRow(
	private val scope: LifecycleCoroutineScope,
	private val api: ApiClient,
	private val navigationRepository: NavigationRepository,
	private val onUp: () -> Unit,
) : HomeFragmentRow, OnItemViewClickedListener {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val adapter = MutableObjectAdapter<MediaBar>(MediaBarPresenter(api, onUp))
		val row = MediaBarRow(adapter)
		rowsAdapter.add(row)

		scope.launch {
			val items = withContext(Dispatchers.IO) {
				runCatching {
					api.itemsApi.getItems(
						includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
						recursive = true,
						sortBy = setOf(ItemSortBy.RANDOM),
						imageTypes = setOf(ImageType.BACKDROP),
						fields = setOf(ItemFields.OVERVIEW, ItemFields.GENRES),
						enableImageTypes = setOf(ImageType.BACKDROP, ImageType.LOGO),
						imageTypeLimit = 1,
						limit = ITEM_COUNT,
					).content.items
				}.onFailure { Timber.w(it, "Unable to load media bar") }.getOrDefault(emptyList())
			}
			if (items.isEmpty()) rowsAdapter.remove(row)
			else adapter.add(MediaBar(items))
		}
	}

	override fun onItemClicked(itemViewHolder: Presenter.ViewHolder?, item: Any?, rowViewHolder: RowPresenter.ViewHolder?, row: Row?) {
		if (item !is MediaBar) return
		navigationRepository.navigate(Destinations.itemDetails(item.current.id))
	}

	companion object {
		private const val ITEM_COUNT = 10

		// No focus zoom and no side padding, so the banner can span the whole screen width.
		val rowPresenter = object : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE) {
			override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
				super.onBindRowViewHolder(holder, item)
				removePadding(holder)
			}

			// ListRowPresenter re-applies its saved vertical padding whenever selection or expansion changes.
			override fun onRowViewSelected(holder: RowPresenter.ViewHolder, selected: Boolean) {
				super.onRowViewSelected(holder, selected)
				removePadding(holder)
				((holder.row as? ListRow)?.adapter as? MutableObjectAdapter<*>)?.filterIsInstance<MediaBar>()?.forEach { it.rowSelected.value = selected }
			}

			override fun onRowViewExpanded(holder: RowPresenter.ViewHolder, expanded: Boolean) {
				super.onRowViewExpanded(holder, expanded)
				removePadding(holder)
			}

			private fun removePadding(holder: RowPresenter.ViewHolder) {
				(holder as ViewHolder).gridView.setPadding(0, 0, 0, 0)
				holder.view.setPadding(0, 0, 0, 0)
			}
		}.apply {
			shadowEnabled = false
			selectEffectEnabled = false
		}
	}

	private class MediaBarPresenter(private val api: ApiClient, private val onUp: () -> Unit) : Presenter() {
		// Same Compose-in-leanback workaround as GridButtonPresenter.
		private class Wrapper(composeView: ComposeView) : FrameLayout(composeView.context) {
			init {
				isFocusable = true
				isFocusableInTouchMode = true
				descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
				addView(composeView)
			}

			override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
				if (isAttachedToWindow) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
				else setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
			}
		}

		private class Holder(val composeView: ComposeView, val wrapper: Wrapper) : ViewHolder(wrapper) {
			val focused = mutableStateOf(false)
		}

		override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
			val composeView = ComposeView(parent.context)
			val holder = Holder(composeView, Wrapper(composeView))
			holder.wrapper.setOnFocusChangeListener { _, hasFocus -> holder.focused.value = hasFocus }
			return holder
		}

		override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
			val holder = viewHolder as Holder
			val bar = item as? MediaBar ?: return
			// Left/right flip through the titles; there is nothing else to move to in this row.
			// Up goes to the toolbar, which overlaps the banner so view focus search never finds it.
			holder.wrapper.setOnKeyListener { _, keyCode, event ->
				val step = when (keyCode) {
					KeyEvent.KEYCODE_DPAD_LEFT -> -1
					KeyEvent.KEYCODE_DPAD_RIGHT -> 1
					KeyEvent.KEYCODE_DPAD_UP -> {
						if (event.action == KeyEvent.ACTION_DOWN) onUp()
						return@setOnKeyListener true
					}
					else -> return@setOnKeyListener false
				}
				if (event.action == KeyEvent.ACTION_DOWN) bar.move(step)
				true
			}
			holder.composeView.setContent {
				val focused by holder.focused
				MediaBarContent(bar, api, focused)
			}
		}

		override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
	}
}

@Composable
private fun MediaBarContent(bar: MediaBar, api: ApiClient, focused: Boolean) {
	val index by bar.index
	LaunchedEffect(index) {
		delay(ROTATE_MILLIS)
		bar.move(1)
	}

	val configuration = LocalConfiguration.current
	// Text and dots hide once scrolled away, where they would end up under the toolbar.
	val rowSelected by bar.rowSelected
	val contentAlpha by animateFloatAsState(if (rowSelected) 1f else 0f, label = "media bar content")
	Box(
		modifier = Modifier.size(configuration.screenWidthDp.dp, (configuration.screenHeightDp * HEIGHT_FRACTION).dp)
	) {
		Crossfade(targetState = bar.items[index], animationSpec = tween(CROSSFADE_MILLIS), label = "media bar") { item ->
			Box(modifier = Modifier.fillMaxSize()) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						// Fade every edge (backdrop and its scrim) to transparent so it melts into the screen behind it.
						.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
						.drawWithContent {
							drawContent()
							drawRect(Brush.verticalGradient(0.6f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
							drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
						},
				) {
					AsyncImage(
						url = api.imageApi.getItemImageUrl(item.id, ImageType.BACKDROP, maxWidth = 1920, tag = item.backdropImageTags?.firstOrNull()),
						aspectRatio = 16f / 9f,
						scaleType = ImageView.ScaleType.CENTER_CROP,
						modifier = Modifier.fillMaxSize(),
					)
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.55f to Color.Transparent))
					)
				}
				Column(
					verticalArrangement = Arrangement.spacedBy(10.dp),
					modifier = Modifier
						.align(Alignment.CenterStart)
						.fillMaxWidth(0.45f)
						.padding(start = 56.dp)
						.alpha(contentAlpha),
				) {
					val logoTag = item.imageTags?.get(ImageType.LOGO)
					if (logoTag != null) {
						AsyncImage(
							url = api.imageApi.getItemImageUrl(item.id, ImageType.LOGO, maxWidth = 800, tag = logoTag),
							scaleType = ImageView.ScaleType.FIT_START,
							modifier = Modifier
								.height(96.dp)
								.widthIn(max = 380.dp),
						)
					} else {
						Text(text = item.name.orEmpty(), color = Color.White, fontSize = 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
					}
					val meta = listOfNotNull(
						item.productionYear?.toString(),
						item.communityRating?.let { "★ " + String.format(Locale.ROOT, "%.1f", it) },
						item.genres?.take(3)?.joinToString(", ")?.ifEmpty { null },
					).joinToString("  ·  ")
					Text(text = meta, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
					Text(text = item.overview.orEmpty(), color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
					// Shows where focus is, since the full-screen banner has no card outline.
					Text(
						text = stringResource(R.string.lbl_more_information),
						color = if (focused) Color.Black else Color.White,
						fontSize = 14.sp,
						modifier = Modifier
							.background(if (focused) Color.White else Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
							.padding(horizontal = 16.dp, vertical = 6.dp),
					)
				}
			}
		}

		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(bottom = 24.dp)
				.alpha(contentAlpha),
		) {
			bar.items.indices.forEach { i ->
				Box(
					modifier = Modifier
						.size(8.dp)
						.background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f), CircleShape)
				)
			}
		}
	}
}
