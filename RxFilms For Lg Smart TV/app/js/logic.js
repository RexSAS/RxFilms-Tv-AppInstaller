// Pure helpers, shared by the app and the node tests in test/logic.test.js.
// Keep to ES2016 + Chromium 53 APIs: webOS 4 TVs run that engine.
var Logic = (function () {
	var SEERR_PARTIALLY_AVAILABLE = 4;
	var SEERR_AVAILABLE = 5;

	/** Seerr search/discover results → titles; only movies and series. */
	function seerrTitles(json) {
		return ((json && json.results) || [])
			.filter(function (e) { return e.mediaType === 'movie' || e.mediaType === 'tv'; })
			.map(function (e) {
				var info = e.mediaInfo || {};
				return {
					id: e.id,
					mediaType: e.mediaType,
					title: e.title || e.name || '',
					year: (e.releaseDate || e.firstAirDate || '').slice(0, 4) || null,
					posterUrl: e.posterPath ? 'https://image.tmdb.org/t/p/w342' + e.posterPath : null,
					status: info.status > 0 ? info.status : null,
					jellyfinId: info.jellyfinMediaId || null,
					rank: null
				};
			});
	}

	/** Jellyfin item to open instead of requesting, when the title is already on the server. */
	function seerrAvailableId(title) {
		var available = title.status === SEERR_AVAILABLE || title.status === SEERR_PARTIALLY_AVAILABLE;
		return available ? title.jellyfinId : null;
	}

	function seerrStatusLabel(status) {
		if (status === SEERR_AVAILABLE) return 'Available';
		if (status === SEERR_PARTIALLY_AVAILABLE) return 'Partially available';
		if (status === 2 || status === 3) return 'Requested';
		return '';
	}

	/** Seerr's trending pages mix movies and series: keep the first [count] of one kind, ranked 1…count. */
	function topTen(pages, movies, count) {
		var seen = {};
		var out = [];
		pages.forEach(function (page) {
			page.forEach(function (t) {
				if ((t.mediaType === 'movie') !== movies || seen[t.id] || out.length >= count) return;
				seen[t.id] = true;
				out.push(Object.assign({}, t, { rank: out.length + 1 }));
			});
		});
		return out;
	}

	// Cards of one row can differ this much in height (a second title line, a status line).
	var ROW_TOLERANCE = 60;

	/**
	 * Index of the rect nearest to [from] in direction [dir], or -1.
	 * Left/right only consider rects on the same line, so the end of a row does not jump into the next one.
	 * Up/down always go to the nearest row first, then to the card closest sideways: otherwise a wide element (the
	 * banner) would jump over a row whose cards start far from its center.
	 */
	function pickNearest(from, rects, dir) {
		var fx = (from.left + from.right) / 2;
		var fy = (from.top + from.bottom) / 2;
		var vertical = dir === 'up' || dir === 'down';
		var scored = [];
		rects.forEach(function (r, i) {
			var primary, secondary;
			if (!vertical) {
				if (r.bottom <= from.top || r.top >= from.bottom) return;
				if (dir === 'right' && r.left < from.right - 2) return;
				if (dir === 'left' && r.right > from.left + 2) return;
				primary = dir === 'right' ? r.left - from.right : from.left - r.right;
				secondary = Math.abs((r.top + r.bottom) / 2 - fy);
			} else {
				if (dir === 'down' && r.top < from.bottom - 2) return;
				if (dir === 'up' && r.bottom > from.top + 2) return;
				primary = Math.max(dir === 'down' ? r.top - from.bottom : from.top - r.bottom, 0);
				secondary = Math.abs((r.left + r.right) / 2 - fx);
			}
			scored.push({ index: i, primary: primary, secondary: secondary });
		});
		if (vertical) {
			var nearestRow = Math.min.apply(null, scored.map(function (s) { return s.primary; }));
			scored = scored.filter(function (s) { return s.primary <= nearestRow + ROW_TOLERANCE; });
		}
		var best = -1;
		var bestScore = Infinity;
		scored.forEach(function (s) {
			var score = vertical ? s.secondary : s.primary + s.secondary * 3;
			if (score < bestScore) {
				bestScore = score;
				best = s.index;
			}
		});
		return best;
	}

	function pad2(n) { return n < 10 ? '0' + n : '' + n; }

	function formatTime(seconds) {
		seconds = Math.max(0, Math.floor(seconds || 0));
		var h = Math.floor(seconds / 3600);
		var m = Math.floor(seconds / 60) % 60;
		var s = seconds % 60;
		return h > 0 ? h + ':' + pad2(m) + ':' + pad2(s) : m + ':' + pad2(s);
	}

	/**
	 * The TV's player always plays the file's default audio track, so another track needs the server to remux.
	 */
	function needsRemux(mediaSource, audioIndex) {
		var audio = (mediaSource.MediaStreams || []).filter(function (s) { return s.Type === 'Audio'; });
		var native = audio.filter(function (s) { return s.IsDefault; })[0] || audio[0];
		return audioIndex != null && audioIndex >= 0 && native != null && native.Index !== audioIndex;
	}

	// Keys go in the URL as ApiKey: newer servers turn the legacy api_key parameter off.
	/** Direct file URL when the TV can play it as is, otherwise the server's HLS transcode. Null: ask again without direct play. */
	function streamFor(server, token, itemId, mediaSource, playSessionId, audioIndex) {
		if (mediaSource.SupportsDirectPlay && !needsRemux(mediaSource, audioIndex)) {
			return {
				method: 'DirectPlay',
				url: server + '/Videos/' + itemId + '/stream?static=true&mediaSourceId=' + mediaSource.Id +
					'&PlaySessionId=' + playSessionId + '&ApiKey=' + token
			};
		}
		if (mediaSource.TranscodingUrl) return { method: 'Transcode', url: server + mediaSource.TranscodingUrl };
		return null;
	}

	return {
		seerrTitles: seerrTitles,
		seerrAvailableId: seerrAvailableId,
		seerrStatusLabel: seerrStatusLabel,
		topTen: topTen,
		pickNearest: pickNearest,
		formatTime: formatTime,
		needsRemux: needsRemux,
		streamFor: streamFor
	};
})();

if (typeof module !== 'undefined') module.exports = Logic;
