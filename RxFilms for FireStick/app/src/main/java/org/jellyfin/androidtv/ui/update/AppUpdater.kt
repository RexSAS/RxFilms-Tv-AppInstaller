package org.jellyfin.androidtv.ui.update

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.dp
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Installs the newest RxFilms TV APK from the GitHub release page. Updates are required: while a newer release
 * exists, a screen that can't be dismissed covers the app until Android's installer has replaced it.
 */
object AppUpdater {
	private const val LATEST_RELEASE = "https://api.github.com/repos/RexSAS/RxFilms-Tv-AppInstaller/releases/latest"
	private const val TV_ASSET_PREFIX = "rxfilms-tv"

	// Check once per app run. [pendingUrl]: accepted, waiting for the install permission.
	// [downloaded]: handed to the installer; if the app comes back, that install didn't happen.
	private var offered = false
	private var pendingUrl: String? = null
	private var downloaded: File? = null
	private var screen: UpdateScreen? = null

	suspend fun <T> check(activity: T) where T : Activity, T : LifecycleOwner {
		// Debug builds have their own package and "dev" versions; only release builds can update themselves.
		if (BuildConfig.DEBUG || offered) return

		val release = withContext(Dispatchers.IO) {
			runCatching { JSONObject(URL(LATEST_RELEASE).readText()) }.onFailure { Timber.w(it, "Update check failed") }.getOrNull()
		} ?: return
		val latest = release.optString("tag_name").removePrefix("v")
		if (!isNewerVersion(latest, BuildConfig.VERSION_NAME)) return
		val assets = release.getJSONArray("assets")
		val url = (0 until assets.length()).map(assets::getJSONObject)
			.firstOrNull { it.getString("name").startsWith(TV_ASSET_PREFIX) && it.getString("name").endsWith(".apk") }
			?.getString("browser_download_url") ?: return
		if (activity.isFinishing || offered) return
		offered = true

		AlertDialog.Builder(activity)
			.setTitle(R.string.update_available_title)
			.setMessage(activity.getString(R.string.update_required_message, latest, BuildConfig.VERSION_NAME))
			.setPositiveButton(R.string.update_install) { _, _ -> activity.lifecycleScope.launch { install(activity, url) } }
			.setCancelable(false)
			.show()
	}

	/** Called from onResume: continue after the install permission, or offer the installer again if it was left. */
	fun <T> resume(activity: T) where T : Activity, T : LifecycleOwner {
		downloaded?.let { apk ->
			screen(activity).showInstall { openInstaller(activity, apk) }
			return
		}
		val url = pendingUrl ?: return
		if (!activity.packageManager.canRequestPackageInstalls()) return
		pendingUrl = null
		activity.lifecycleScope.launch { install(activity, url) }
	}

	private suspend fun <T> install(activity: T, url: String) where T : Activity, T : LifecycleOwner {
		if (!activity.packageManager.canRequestPackageInstalls()) {
			pendingUrl = url
			// Fire OS has no per-app permission page; the switch lives under Developer options.
			AlertDialog.Builder(activity)
				.setTitle(R.string.update_available_title)
				.setMessage(R.string.update_allow_install)
				.setPositiveButton(R.string.update_open_settings) { _, _ ->
					runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
						.recoverCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
				}
				.setCancelable(false)
				.show()
			return
		}

		val screen = screen(activity)
		screen.showDownloading()
		val apk = withContext(Dispatchers.IO) {
			runCatching {
				File(activity.cacheDir, "updates").apply { mkdirs() }.resolve("rxfilms-tv.apk").also { file ->
					download(url, file) { done, total -> withContext(Dispatchers.Main) { screen.progress(done, total) } }
				}
			}.onFailure { Timber.w(it, "Update download failed") }.getOrNull()
		}
		if (apk == null) {
			screen.showFailed { activity.lifecycleScope.launch { install(activity, url) } }
			return
		}

		downloaded = apk
		openInstaller(activity, apk)
	}

	private fun openInstaller(activity: Activity, apk: File) {
		val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
		activity.startActivity(
			Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		)
	}

	// One screen per activity: a recreated activity gets a fresh one.
	private fun screen(activity: Activity): UpdateScreen =
		screen?.takeIf { it.activity == activity } ?: UpdateScreen(activity).also { screen = it }
}

/** Copies [url] to [file], reporting bytes done and the total (-1 when the server doesn't say). */
private suspend fun download(url: String, file: File, onProgress: suspend (done: Long, total: Long) -> Unit) {
	val connection = URL(url).openConnection()
	val total = connection.contentLengthLong
	connection.getInputStream().use { input ->
		file.outputStream().use { output ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
			var done = 0L
			var reported = -1L
			// About 1% steps (or every 256 KB when the size is unknown), not every buffer.
			val step = if (total > 0) total / 100 else 256 * 1024L
			while (true) {
				coroutineContext.ensureActive()
				val read = input.read(buffer)
				if (read < 0) break
				output.write(buffer, 0, read)
				done += read
				if (done - reported >= step) {
					reported = done
					onProgress(done, total)
				}
			}
			onProgress(done, total)
		}
	}
}

/** Full-screen update screen covering the app; Back does nothing. */
private class UpdateScreen(val activity: Activity) {
	private val title = TextView(activity).apply {
		textSize = 24f
		setTextColor(Color.WHITE)
		gravity = Gravity.CENTER
	}
	private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
	private val label = TextView(activity).apply {
		textSize = 16f
		setTextColor(Color.LTGRAY)
		gravity = Gravity.CENTER
	}
	private val button = Button(activity)
	private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
		setCancelable(false)
		setContentView(LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER
			setBackgroundColor(Color.BLACK)
			val gap = 16.dp(activity)
			addView(title)
			addView(bar, LinearLayout.LayoutParams(480.dp(activity), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap * 2 })
			addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
			addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap * 2 })
		})
	}

	fun showDownloading() {
		title.setText(R.string.update_downloading)
		bar.visibility = View.VISIBLE
		bar.isIndeterminate = true
		label.text = null
		button.visibility = View.GONE
		show()
	}

	fun progress(done: Long, total: Long) {
		val size = { bytes: Long -> Formatter.formatShortFileSize(activity, bytes) }
		if (total > 0) {
			val percent = (done * 100 / total).toInt().coerceIn(0, 100)
			bar.isIndeterminate = false
			bar.progress = percent
			label.text = activity.getString(R.string.update_progress, percent, size(done), size(total))
		} else label.text = size(done)
	}

	fun showFailed(retry: () -> Unit) = showAction(R.string.update_failed, R.string.update_retry, retry)

	fun showInstall(install: () -> Unit) = showAction(R.string.update_install_waiting, R.string.update_install, install)

	private fun showAction(message: Int, action: Int, onClick: () -> Unit) {
		title.setText(message)
		bar.visibility = View.GONE
		label.text = null
		button.setText(action)
		button.visibility = View.VISIBLE
		button.setOnClickListener { onClick() }
		show()
		button.requestFocus()
	}

	private fun show() {
		if (!dialog.isShowing && !activity.isFinishing) dialog.show()
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
