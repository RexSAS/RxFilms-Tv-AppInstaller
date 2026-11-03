package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.util.WireGuardTunnel

class JellyfinApplication : Application() {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		TelemetryService.init(this)
	}

	override fun onCreate() {
		super.onCreate()
		ProcessLifecycleOwner.get().lifecycle.addObserver(WireGuardTunnel.lifecycleObserver(this))
	}
}
