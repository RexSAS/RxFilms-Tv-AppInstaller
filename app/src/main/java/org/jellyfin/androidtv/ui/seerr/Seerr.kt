package org.jellyfin.androidtv.ui.seerr

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.util.dp
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A movie or series known to Seerr; [status] is Seerr's media status (2 pending … 5 available), null if never requested. */
data class SeerrTitle(
	val id: Int,
	val mediaType: String,
	val title: String,
	val year: String?,
	val posterPath: String?,
	val status: Int?,
	val jellyfinId: UUID?,
	/** Position in a Top 10 list, drawn over the poster. */
	val rank: Int? = null,
) {
	val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
	val isMovie get() = mediaType == "movie"

	/** Jellyfin item to open instead of requesting, when the title is already on the server. */
	val availableItemId get() = jellyfinId?.takeIf { status == STATUS_AVAILABLE || status == STATUS_PARTIALLY_AVAILABLE }

	companion object {
		const val STATUS_PARTIALLY_AVAILABLE = 4
		const val STATUS_AVAILABLE = 5
	}
}

/**
 * Minimal Seerr (Jellyseerr/Overseerr) API client.
 * shortcut: server and API key are build-time settings (rxfilms.seerr.url / rxfilms.seerr.key) and requests are made
 * as the key's user; move to a settings screen or the Jellyfin Enhanced proxy if other people use the app.
 */
object Seerr {
	private const val BASE_URL = BuildConfig.SEERR_URL
	private const val API_KEY = BuildConfig.SEERR_API_KEY
	private const val TIMEOUT_MILLIS = 10_000
	private const val TRENDING_MAX_PAGES = 5

	suspend fun discoverMovies() = titles(get("/api/v1/discover/movies"))
	suspend fun discoverSeries() = titles(get("/api/v1/discover/tv"))

	/** Seerr's trending list mixes movies and series, so read pages until there are [count] of the wanted type. */
	suspend fun trending(movies: Boolean, count: Int = 10): List<SeerrTitle> {
		val result = mutableListOf<SeerrTitle>()
		for (page in 1..TRENDING_MAX_PAGES) {
			result += titles(get("/api/v1/discover/trending?page=$page")).filter { it.isMovie == movies }
			if (result.size >= count) break
		}
		return result.distinctBy { it.id }.take(count).mapIndexed { index, title -> title.copy(rank = index + 1) }
	}

	// Seerr rejects "+" for spaces, so encode them as %20.
	suspend fun search(query: String) = titles(get("/api/v1/search?query=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")))

	suspend fun request(title: SeerrTitle) {
		val body = JSONObject().put("mediaType", title.mediaType).put("mediaId", title.id)
		if (!title.isMovie) body.put("seasons", "all")
		call("POST", "/api/v1/request", body.toString())
	}

	private suspend fun get(path: String) = JSONObject(call("GET", path, null))

	private suspend fun call(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
		val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
		try {
			connection.requestMethod = method
			connection.connectTimeout = TIMEOUT_MILLIS
			connection.readTimeout = TIMEOUT_MILLIS
			connection.setRequestProperty("X-Api-Key", API_KEY)
			connection.setRequestProperty("Content-Type", "application/json")
			if (body != null) {
				connection.doOutput = true
				connection.outputStream.use { it.write(body.toByteArray()) }
			}
			if (connection.responseCode !in 200..299) error("Seerr $method $path failed: ${connection.responseCode}")
			connection.inputStream.bufferedReader().use { it.readText() }
		} finally {
			connection.disconnect()
		}
	}

	private fun titles(json: JSONObject): List<SeerrTitle> {
		val results = json.optJSONArray("results") ?: JSONArray()
		return (0 until results.length()).map(results::getJSONObject)
			.filter { it.optString("mediaType") == "movie" || it.optString("mediaType") == "tv" }
			.map { entry ->
				SeerrTitle(
					id = entry.getInt("id"),
					mediaType = entry.getString("mediaType"),
					title = entry.optString("title").ifEmpty { entry.optString("name") },
					year = entry.optString("releaseDate").ifEmpty { entry.optString("firstAirDate") }.take(4).ifEmpty { null },
					posterPath = entry.optString("posterPath").ifEmpty { null },
					status = entry.optJSONObject("mediaInfo")?.optInt("status")?.takeIf { it > 0 },
					jellyfinId = entry.optJSONObject("mediaInfo")?.optString("jellyfinMediaId")?.toUUIDOrNull(),
				)
			}
	}
}

/** Poster card for a [SeerrTitle]: poster, title and its Seerr status. */
class SeerrCardPresenter : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val context = parent.context
		val border = GradientDrawable().apply { cornerRadius = 6.dp(context).toFloat() }
		val poster = AsyncImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		// Darkens the bottom of the poster so the rank number stays readable.
		val rankShade = View(context).apply {
			background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 0, 0)))
		}
		val rank = TextView(context).apply {
			textSize = 64f
			typeface = Typeface.DEFAULT_BOLD
			includeFontPadding = false
			setShadowLayer(12f, 0f, 0f, Color.BLACK)
		}
		val title = TextView(context).apply {
			setTextColor(Color.WHITE)
			textSize = 14f
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
		}
		val status = TextView(context).apply {
			setTextColor(Color.parseColor("#FFC107"))
			textSize = 12f
		}
		val card = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			isFocusable = true
			isFocusableInTouchMode = true
			layoutParams = ViewGroup.LayoutParams(CARD_WIDTH.dp(context), ViewGroup.LayoutParams.WRAP_CONTENT)
			addView(FrameLayout(context).apply {
				foreground = border
				addView(poster, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
				addView(rankShade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (CARD_WIDTH * 3 / 4).dp(context), Gravity.BOTTOM))
				addView(rank, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
					marginStart = 6.dp(context)
				})
			}, LinearLayout.LayoutParams(CARD_WIDTH.dp(context), (CARD_WIDTH * 3 / 2).dp(context)))
			addView(title)
			addView(status)
			setOnFocusChangeListener { _, focused -> border.setStroke(if (focused) 3.dp(context) else 0, Color.WHITE) }
		}
		return ViewHolder(card)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		val title = item as? SeerrTitle ?: return
		val card = viewHolder.view as LinearLayout
		val posterFrame = card.getChildAt(0) as FrameLayout
		(posterFrame.getChildAt(0) as AsyncImageView).load(url = title.posterUrl, aspectRatio = 2.0 / 3.0)
		posterFrame.getChildAt(1).visibility = if (title.rank != null) View.VISIBLE else View.GONE
		(posterFrame.getChildAt(2) as TextView).apply {
			visibility = if (title.rank != null) View.VISIBLE else View.GONE
			text = title.rank?.toString()
			setTextColor(
				when (title.rank) {
					1 -> Color.parseColor("#FFD700")
					2 -> Color.parseColor("#C0C0C0")
					3 -> Color.parseColor("#CD7F32")
					else -> Color.WHITE
				}
			)
		}
		(card.getChildAt(1) as TextView).text = listOfNotNull(title.title, title.year?.let { "($it)" }).joinToString(" ")
		(card.getChildAt(2) as TextView).text = when (title.status) {
			5 -> "Available"
			4 -> "Partially available"
			2, 3 -> "Requested"
			else -> ""
		}
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit

	private companion object {
		const val CARD_WIDTH = 120
	}
}
