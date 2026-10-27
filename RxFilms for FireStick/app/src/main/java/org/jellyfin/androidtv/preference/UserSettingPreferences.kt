package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.preference.store.DisplayPreferencesStore
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.intPreference
import org.jellyfin.sdk.api.client.ApiClient

class UserSettingPreferences(
	api: ApiClient,
) : DisplayPreferencesStore(
	displayPreferencesId = "usersettings",
	api = api,
	app = "emby",
) {
	companion object {
		val skipBackLength = intPreference("skipBackLength", 10_000)
		val skipForwardLength = intPreference("skipForwardLength", 30_000)

		val homesection0 = enumPreference("homesection0", HomeSectionType.MEDIA_BAR)
		val homesection1 = enumPreference("homesection1", HomeSectionType.RESUME)
		val homesection2 = enumPreference("homesection2", HomeSectionType.NEXT_UP)
		val homesection3 = enumPreference("homesection3", HomeSectionType.TOP_10)
		val homesection4 = enumPreference("homesection4", HomeSectionType.SEERR_RECOMMENDED)
		val homesection5 = enumPreference("homesection5", HomeSectionType.BECAUSE_YOU_WATCHED)
		val homesection6 = enumPreference("homesection6", HomeSectionType.LATEST_MEDIA)
		val homesection7 = enumPreference("homesection7", HomeSectionType.FRANCHISES)
		val homesection8 = enumPreference("homesection8", HomeSectionType.GENRES)
		val homesection9 = enumPreference("homesection9", HomeSectionType.LIVE_TV)
	}

	val homesections = listOf(
		homesection0,
		homesection1,
		homesection2,
		homesection3,
		homesection4,
		homesection5,
		homesection6,
		homesection7,
		homesection8,
		homesection9,
	)

	val activeHomesections
		get() = homesections
			.map(::get)
			.filterNot { it == HomeSectionType.NONE }
}
