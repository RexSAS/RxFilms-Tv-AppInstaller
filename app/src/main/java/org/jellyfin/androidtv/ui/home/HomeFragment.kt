package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.koin.android.ext.android.inject

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val rowsFocusRequester = remember { FocusRequester() }
		val toolbarFocusRequester = remember { FocusRequester() }
		// Set while we move focus to the toolbar ourselves, so the exit guard below lets it through.
		val toolbarFocusRequest = remember { booleanArrayOf(false) }
		val focusToolbar = {
			toolbarFocusRequest[0] = true
			toolbarFocusRequester.requestFocus()
			toolbarFocusRequest[0] = false
		}
		LaunchedEffect(rowsFocusRequester) { rowsFocusRequester.requestFocus() }

		// The rows fill the whole screen so the media bar can sit behind the toolbar; the toolbar gets a shadow to stay readable.
		Box {

			// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
			// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
			// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
			var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }
			AndroidFragment<HomeRowsFragment>(
				modifier = Modifier
					// The toolbar overlaps the rows, so focus search never finds it "above" the first row; move there directly.
					.onPreviewKeyEvent { event ->
						val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
						if (event.key != Key.DirectionUp || !isFirstRowSelected) return@onPreviewKeyEvent false
						if (event.type == KeyEventType.KeyDown) focusToolbar()
						true
					}
					.focusGroup()
					.focusRequester(rowsFocusRequester)
					.focusProperties {
						onExit = {
							val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (!toolbarFocusRequest[0] && (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected)) {
								cancelFocusChange()
							} else {
								rowsSupportFragment?.selectedPosition = 0
								rowsSupportFragment?.verticalGridView?.clearFocus()
							}
						}
					}
					.fillMaxSize(),
				onUpdate = { fragment ->
					rowsSupportFragment = fragment
					fragment.onFocusToolbar = focusToolbar
				}
			)

			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
					// Same overlap problem in the other direction: the rows are never "below" the toolbar.
					.onPreviewKeyEvent { event ->
						if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
						if (event.type == KeyEventType.KeyDown) rowsFocusRequester.requestFocus()
						true
					}
			) {
				MainToolbar(MainToolbarActiveButton.Home, homeFocusRequester = toolbarFocusRequester)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}
}
