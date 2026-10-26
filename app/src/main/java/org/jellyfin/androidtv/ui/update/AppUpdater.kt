package org.jellyfin.androidtv.ui.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URL

/** Offers the newest RxFilms TV APK from the GitHub release page and hands it to the system installer. */
object AppUpdater {
	private const val LATEST_RELEASE = "https://api.github.com/repos/RexSAS/RxFilms-Tv-AppInstaller/releases/latest"
	private const val TV_ASSET_PREFIX = "rxfilms-tv"

	suspend fun <T> check(activity: T) where T : Activity, T : LifecycleOwner {
		// Debug builds have their own package and "dev" versions; only release builds can update themselves.
		if (BuildConfig.DEBUG) return

		val release = withContext(Dispatchers.IO) {
			runCatching { JSONObject(URL(LATEST_RELEASE).readText()) }.onFailure { Timber.w(it, "Update check failed") }.getOrNull()
		} ?: return
		val latest = release.optString("tag_name").removePrefix("v")
		if (!isNewerVersion(latest, BuildConfig.VERSION_NAME)) return
		val assets = release.getJSONArray("assets")
		val url = (0 until assets.length()).map(assets::getJSONObject)
			.firstOrNull { it.getString("name").startsWith(TV_ASSET_PREFIX) && it.getString("name").endsWith(".apk") }
			?.getString("browser_download_url") ?: return
		if (activity.isFinishing) return

		AlertDialog.Builder(activity)
			.setTitle(R.string.update_available_title)
			.setMessage(activity.getString(R.string.update_available_message, latest, BuildConfig.VERSION_NAME))
			.setPositiveButton(R.string.update_install) { _, _ -> activity.lifecycleScope.launch { install(activity, url) } }
			.setNegativeButton(R.string.update_later, null)
			.show()
	}

	private suspend fun install(activity: Activity, url: String) {
		if (!activity.packageManager.canRequestPackageInstalls()) {
			Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show()
			runCatching {
				activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
			}
			return
		}

		Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_LONG).show()
		val apk = withContext(Dispatchers.IO) {
			runCatching {
				File(activity.cacheDir, "updates").apply { mkdirs() }.resolve("rxfilms-tv.apk").also { file ->
					URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
				}
			}.onFailure { Timber.w(it, "Update download failed") }.getOrNull()
		}
		if (apk == null) {
			Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
			return
		}

		val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
		activity.startActivity(
			Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		)
	}
}

/** Compares "major.minor.patch" versions, ignoring pre-release suffixes like "-dev.1". */
internal fun isNewerVersion(latest: String, current: String): Boolean {
	fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
	val a = parts(latest)
	val b = parts(current)
	for (i in 0 until maxOf(a.size, b.size)) {
		val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
		if (diff != 0) return diff > 0
	}
	return false
}
