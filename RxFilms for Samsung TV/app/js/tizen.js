// Samsung Tizen glue: the Return key is 10009 (not webOS's 461), media keys must be registered, and the app exits
// through the Tizen API instead of window.close().
Keys.BACK = 10009;
Keys.PLAYPAUSE = 10252;

if (window.tizen) {
	['MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaStop', 'MediaFastForward', 'MediaRewind'].forEach(function (key) {
		try {
			tizen.tvinputdevice.registerKey(key);
		} catch (e) {}
	});
	window.close = function () { tizen.application.getCurrentApplication().exit(); };
}
