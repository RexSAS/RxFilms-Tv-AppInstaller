package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.player.base.PlayerHeader
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

// Mirrors the legacy player header: logo replaces the title, episodes show series + "S1:E2 - Name"
@Composable
@Stable
fun VideoPlayerHeader(
	item: BaseItemDto?,
	api: ApiClient = koinInject(),
) {
	PlayerHeader {
		if (item != null) {
			val episode = item.type == BaseItemKind.EPISODE
			val logo = item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO]

			if (logo != null) {
				AsyncImage(
					modifier = Modifier
						.height(60.dp)
						.padding(bottom = 8.dp),
					url = logo.getUrl(api),
					blurHash = logo.blurHash,
					aspectRatio = logo.aspectRatio ?: 1f,
				)
			} else {
				Text(
					text = (if (episode) item.seriesName else item.name).orEmpty(),
					overflow = TextOverflow.Ellipsis,
					maxLines = 1,
					style = LocalTextStyle.current.copy(
						color = Color.White,
						fontSize = 32.sp
					)
				)
			}

			if (episode) {
				Text(
					text = item.getDisplayName(LocalContext.current),
					overflow = TextOverflow.Ellipsis,
					maxLines = 1,
					style = LocalTextStyle.current.copy(
						color = Color.White,
						fontSize = 20.sp
					)
				)
			}
		}
	}
}
