package org.jellyfin.androidtv.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Keeps the tunnel named [TUNNEL] of the official WireGuard app up while RxFilms is in the foreground.
 * Devices without WireGuard never hold [PERMISSION], so everything here does nothing on them.
 */
object WireGuardTunnel {
	const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"

	private const val PACKAGE = "com.wireguard.android"
	private const val RECEIVER = "$PACKAGE.model.TunnelManager\$IntentReceiver"
	private const val TUNNEL = "rxfilms"
	private const val CONNECT_TIMEOUT_MS = 5_000L
	private const val CONNECT_POLL_MS = 200L

	private var lastEnabled: Boolean? = null

	fun isInstalled(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

	fun setEnabled(context: Context, enabled: Boolean) {
		// A repeated "up" restarts the tunnel and can leave a dead interface behind
		if (!canControl(context) || lastEnabled == enabled) return
		lastEnabled = enabled

		val action = if (enabled) "SET_TUNNEL_UP" else "SET_TUNNEL_DOWN"
		Timber.i("WireGuard: $action $TUNNEL")
		context.sendBroadcast(
			Intent("$PACKAGE.action.$action")
				.setComponent(ComponentName(PACKAGE, RECEIVER))
				.putExtra("tunnel", TUNNEL)
		)
	}

	/** Waits for the VPN so the first server request doesn't fail, then carries on either way. */
	suspend fun awaitConnected(context: Context) {
		if (!canControl(context)) return

		val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
			while (!isVpnActive(context)) delay(CONNECT_POLL_MS)
			true
		}
		Timber.i("WireGuard: connected=${connected == true}")
	}

	// shortcut: also drops the tunnel for background audio and external players, keep it up during playback if those get used on a VPN TV.
	fun lifecycleObserver(context: Context) = object : DefaultLifecycleObserver {
		override fun onStart(owner: LifecycleOwner) = setEnabled(context, true)
		override fun onStop(owner: LifecycleOwner) = setEnabled(context, false)
	}

	private fun canControl(context: Context) =
		ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

	private fun isVpnActive(context: Context): Boolean {
		val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return false
		// A VPN that only routes the server is not the active network, so look through all of them
		return connectivityManager.allNetworks.any { network ->
			connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
		}
	}
}
