package org.rxfilms.installer

import android.app.Activity
import android.net.ConnectivityManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Finds TVs with network ADB on (port 5555), then replaces the Jellyfin app on the chosen TV with the
 * RxFilms APK from the latest GitHub release.
 */
class MainActivity : Activity() {
	private val scope = MainScope()
	private lateinit var tvList: LinearLayout
	private lateinit var log: TextView
	private var busy = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val padding = (16 * resources.displayMetrics.density).toInt()
		val content = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, padding, padding, padding)
		}
		content.addView(TextView(this).apply {
			text = getString(R.string.intro)
			textSize = 16f
		})
		content.addView(Button(this).apply {
			text = getString(R.string.scan)
			setOnClickListener { scan() }
		})
		tvList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		content.addView(tvList)
		val manualIp = EditText(this).apply {
			hint = getString(R.string.manual_hint)
			inputType = InputType.TYPE_CLASS_PHONE
		}
		content.addView(manualIp)
		content.addView(Button(this).apply {
			text = getString(R.string.install_manual)
			setOnClickListener { manualIp.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::install) }
		})
		log = TextView(this).apply { setTextIsSelectable(true) }
		content.addView(log)

		setContentView(ScrollView(this).apply {
			addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		})
		scan()
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	private fun log(message: String) {
		log.append(message + "\n")
	}

	private fun scan() {
		if (busy) return
		busy = true
		tvList.removeAllViews()
		log(getString(R.string.scanning))
		scope.launch {
			val found = findAdbHosts()
			if (found.isEmpty()) log(getString(R.string.none_found))
			for (host in found) {
				tvList.addView(Button(this@MainActivity).apply {
					text = getString(R.string.install_on, host)
					setOnClickListener { install(host) }
				})
			}
			busy = false
		}
	}

	/** Every host on the phone's /24 Wi-Fi network that accepts a connection on the ADB port. */
	private suspend fun findAdbHosts(): List<String> {
		val connectivity = getSystemService(ConnectivityManager::class.java)
		val address = connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses
			?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull() ?: return emptyList()
		val prefix = address.hostAddress!!.substringBeforeLast('.')
		val permits = Semaphore(SCAN_PARALLELISM)
		return withContext(Dispatchers.IO) {
			(1..254).map { "$prefix.$it" }.map { host ->
				async { permits.withPermit { host.takeIf { isPortOpen(it) } } }
			}.awaitAll().filterNotNull()
		}
	}

	private fun isPortOpen(host: String) = runCatching {
		Socket().use { it.connect(InetSocketAddress(host, ADB_PORT), SCAN_TIMEOUT_MILLIS) }
	}.isSuccess

	private fun install(host: String) {
		if (busy) return
		busy = true
		scope.launch {
			try {
				log(getString(R.string.downloading))
				val apk = withContext(Dispatchers.IO) { downloadLatestTvApk() }
				log(getString(R.string.connecting, host))
				withContext(Dispatchers.IO) {
					Dadb.create(host, ADB_PORT, keyPair()).use { dadb ->
						// Replace the original Jellyfin app (or an RxFilms build from before the package rename).
						if (dadb.shell("pm path $LEGACY_PACKAGE").output.isNotBlank()) {
							withContext(Dispatchers.Main) { log(getString(R.string.replacing)) }
							dadb.uninstall(LEGACY_PACKAGE)
						}
						dadb.install(apk)
						dadb.shell("monkey -p $TV_PACKAGE -c android.intent.category.LEANBACK_LAUNCHER 1")
					}
				}
				log(getString(R.string.done))
			} catch (error: Exception) {
				log(getString(R.string.failed, error.message ?: error.javaClass.simpleName))
			} finally {
				busy = false
			}
		}
	}

	/** The TV only has to approve this key once ("Always allow from this computer"). */
	private fun keyPair(): AdbKeyPair {
		val private = File(filesDir, "adbkey")
		val public = File(filesDir, "adbkey.pub")
		if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
		return AdbKeyPair.read(private, public)
	}

	private fun downloadLatestTvApk(): File {
		val release = JSONObject(URL("https://api.github.com/repos/$REPOSITORY/releases/latest").readText())
		val assets = release.getJSONArray("assets")
		val url = (0 until assets.length()).map(assets::getJSONObject)
			.firstOrNull { it.getString("name").startsWith(TV_ASSET_PREFIX) && it.getString("name").endsWith(".apk") }
			?.getString("browser_download_url")
			?: error(getString(R.string.no_asset, release.optString("tag_name")))
		val file = File(cacheDir, "rxfilms-tv.apk")
		URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
		return file
	}

	private companion object {
		const val REPOSITORY = "RexSAS/RxFilms-Tv-AppInstaller"
		const val TV_ASSET_PREFIX = "rxfilms-tv"
		const val TV_PACKAGE = "org.rxfilms.tv"
		const val LEGACY_PACKAGE = "org.jellyfin.androidtv"
		const val ADB_PORT = 5555
		const val SCAN_TIMEOUT_MILLIS = 400
		const val SCAN_PARALLELISM = 64
	}
}
