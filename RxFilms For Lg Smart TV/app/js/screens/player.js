// Video player on the TV's own <video> pipeline: direct play when webOS supports the file, HLS transcode otherwise.
// DTS and AV1 are left to the server: only some LG models decode them.
var DEVICE_PROFILE = {
	Name: 'RxFilms webOS',
	MaxStreamingBitrate: 120000000,
	MaxStaticBitrate: 120000000,
	DirectPlayProfiles: [
		{ Type: 'Video', Container: 'mp4,m4v,mkv,webm,mov,ts,mpegts', VideoCodec: 'h264,hevc,vp9', AudioCodec: 'aac,mp3,ac3,eac3,opus,flac,vorbis' }
	],
	TranscodingProfiles: [
		{ Type: 'Video', Container: 'ts', VideoCodec: 'h264,hevc', AudioCodec: 'aac,ac3,eac3,mp3', Protocol: 'hls', Context: 'Streaming', MaxAudioChannels: '6', MinSegments: 1, BreakOnNonKeyFrames: true }
	],
	ContainerProfiles: [],
	CodecProfiles: [],
	// Text subtitles are fetched as WebVTT by the app; picture subtitles are burned in by the server.
	SubtitleProfiles: ['vtt', 'srt', 'subrip', 'ass', 'ssa', 'webvtt'].map(function (format) { return { Format: format, Method: 'External' }; })
		.concat(['pgssub', 'pgs', 'dvdsub', 'dvbsub', 'vobsub'].map(function (format) { return { Format: format, Method: 'Encode' }; }))
};

// Picture modes for the ▲ menu, as CSS object-fit (+ a zoom for "Zoom").
var VIDEO_FITS = [
	{ label: 'Fit', fit: 'contain' },
	{ label: 'Fill (crop)', fit: 'cover' },
	{ label: 'Stretch', fit: 'fill' },
	{ label: 'Zoom', fit: 'contain', zoom: 1.33 }
];

/** [sync] = {playlistItemId} when SyncPlay opened this player: the group then drives play, pause and seek. */
function PlayerScreen(item, startSeconds, sync) {
	var SEEK_BACK = 10;
	var SEEK_FORWARD = 30;
	var el = h('div', 'screen player');
	var video = h('video');
	video.autoplay = !sync;
	var top = h('div', 'osd-top');
	top.appendChild(h('div', 'osd-title', item.Type === 'Episode' ? item.SeriesName : item.Name));
	if (item.Type === 'Episode') top.appendChild(h('div', 'osd-subtitle', UI.episodeLabel(item)));
	if (sync) top.appendChild(h('div', 'osd-subtitle', 'SyncPlay'));
	var osd = h('div', 'osd');
	var bar = h('div', 'osd-bar');
	var fill = h('div', 'osd-fill');
	var times = h('div', 'osd-times');
	var hint = h('div', 'osd-hint', 'OK pause  ·  ◀ ▶ seek  ·  ▲ audio, subtitles & picture  ·  Back stop');
	var spinner = h('div', 'spinner', 'Loading…');
	bar.appendChild(fill);
	[bar, times, hint].forEach(function (node) { osd.appendChild(node); });
	[video, top, osd, spinner].forEach(function (node) { el.appendChild(node); });

	var duration = item.RunTimeTicks ? item.RunTimeTicks / 1e7 : 0;
	var state = { audio: null, sub: null, source: null, session: null, method: null, reported: false, syncWaiting: false };
	var pendingSeek = 0;
	var seekTarget = null;
	var timers = {};

	function applyFit() {
		var mode = VIDEO_FITS[+localStorage.getItem('videoFit') || 0] || VIDEO_FITS[0];
		video.style.objectFit = mode.fit;
		video.style.transform = mode.zoom ? 'scale(' + mode.zoom + ')' : '';
	}
	applyFit();

	function playbackInfo(start, directPlay) {
		return Api.post('/Items/' + item.Id + '/PlaybackInfo', {
			UserId: Api.userId,
			DeviceProfile: DEVICE_PROFILE,
			StartTimeTicks: Math.floor(start * 1e7),
			AudioStreamIndex: state.audio,
			SubtitleStreamIndex: state.sub,
			MediaSourceId: state.source ? state.source.Id : null,
			MaxStreamingBitrate: DEVICE_PROFILE.MaxStreamingBitrate,
			EnableDirectPlay: directPlay,
			EnableDirectStream: true,
			EnableTranscoding: true,
			AutoOpenLiveStream: true
		});
	}

	function load(start, directPlay) {
		spinner.style.display = '';
		stopReporting();
		return playbackInfo(start, directPlay).then(function (info) {
			var source = info.MediaSources && info.MediaSources[0];
			if (!source) throw new Error('No playable version');
			if (state.audio == null) state.audio = source.DefaultAudioStreamIndex;
			if (state.sub == null) state.sub = source.DefaultSubtitleStreamIndex != null ? source.DefaultSubtitleStreamIndex : -1;
			var stream = Logic.streamFor(Api.server, Api.token, item.Id, source, info.PlaySessionId, state.audio);
			if (!stream) {
				// Another audio track than the file's default: let the server remux it.
				if (!directPlay) throw new Error('The server offered no stream');
				return load(start, false);
			}
			state.source = source;
			state.session = info.PlaySessionId;
			state.method = stream.method;
			state.syncWaiting = !!sync;
			pendingSeek = start;
			video.src = stream.url;
			setSubtitles();
		}).catch(function (error) {
			console.warn(error);
			UI.toast('Unable to play: ' + error.message);
			if (!state.destroyed) App.back();
		});
	}

	function subtitleStream(index) {
		return (state.source.MediaStreams || []).filter(function (s) { return s.Type === 'Subtitle' && s.Index === index; })[0];
	}

	function setSubtitles() {
		Array.prototype.slice.call(video.querySelectorAll('track')).forEach(function (track) { video.removeChild(track); });
		var stream = subtitleStream(state.sub);
		if (!stream || !stream.IsTextSubtitleStream) return;
		var sub = state.sub;
		// Fetched into a blob so the <video> needs no CORS mode; the server converts any text format to WebVTT.
		fetch(Api.server + '/Videos/' + item.Id + '/' + state.source.Id + '/Subtitles/' + stream.Index + '/0/Stream.vtt?ApiKey=' + Api.token)
			.then(function (response) { return response.text(); })
			.then(function (text) {
				if (sub !== state.sub) return;
				var track = h('track');
				track.kind = 'subtitles';
				track.default = true;
				track.src = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
				video.appendChild(track);
				track.track.mode = 'showing';
			}).catch(function (error) { console.warn('Unable to load subtitles', error); });
	}

	function restart() { load(video.currentTime || pendingSeek, true); }

	function trackMenu() {
		if (!state.source) return;
		var streams = state.source.MediaStreams || [];
		var options = streams.filter(function (s) { return s.Type === 'Audio'; }).map(function (s) {
			return {
				label: 'Audio: ' + (s.DisplayTitle || s.Language || 'Track ' + s.Index),
				checked: s.Index === state.audio,
				action: function () {
					if (s.Index === state.audio) return;
					state.audio = s.Index;
					restart();
				}
			};
		});
		options.push({ label: 'Subtitles: Off', checked: state.sub === -1, action: function () { switchSubtitles(-1); } });
		streams.filter(function (s) { return s.Type === 'Subtitle'; }).forEach(function (s) {
			options.push({
				label: 'Subtitles: ' + (s.DisplayTitle || s.Language || 'Track ' + s.Index),
				checked: s.Index === state.sub,
				action: function () { switchSubtitles(s.Index); }
			});
		});
		var fit = +localStorage.getItem('videoFit') || 0;
		VIDEO_FITS.forEach(function (mode, index) {
			options.push({
				label: 'Picture: ' + mode.label,
				checked: index === fit,
				action: function () {
					localStorage.setItem('videoFit', index);
					applyFit();
				}
			});
		});
		UI.menu('Audio, subtitles & picture', options);
	}

	// Picture subtitles are burned in by the server, so switching to or from one restarts the stream.
	function switchSubtitles(index) {
		if (index === state.sub) return;
		var previous = subtitleStream(state.sub);
		var next = subtitleStream(index);
		state.sub = index;
		if ((previous && !previous.IsTextSubtitleStream) || (next && !next.IsTextSubtitleStream)) restart();
		else setSubtitles();
	}

	function report(path) {
		if (!state.session) return;
		Api.post('/Sessions/Playing' + path, {
			ItemId: item.Id,
			MediaSourceId: state.source.Id,
			PlaySessionId: state.session,
			PositionTicks: Math.floor((video.currentTime || 0) * 1e7),
			PlayMethod: state.method,
			AudioStreamIndex: state.audio,
			SubtitleStreamIndex: state.sub,
			IsPaused: video.paused,
			CanSeek: true
		}).catch(function () {});
	}

	function stopReporting() {
		clearInterval(timers.progress);
		if (!state.reported) return;
		state.reported = false;
		report('/Stopped');
		Api.del('/Videos/ActiveEncodings', { deviceId: Api.deviceId, playSessionId: state.session }).catch(function () {});
	}

	function updateOsd() {
		var position = seekTarget != null ? seekTarget : video.currentTime;
		var total = duration || video.duration || 0;
		fill.style.width = total ? Math.min(100, position / total * 100) + '%' : '0';
		times.textContent = Logic.formatTime(position) + ' / ' + Logic.formatTime(total);
	}

	function showOsd() {
		updateOsd();
		el.classList.add('osd-shown');
		clearTimeout(timers.osd);
		timers.osd = setTimeout(function () { if (!video.paused) el.classList.remove('osd-shown'); }, 4000);
	}

	function inGroup() { return sync && SyncPlay.group; }

	// Repeated presses add up and seek once, so a transcode is not restarted for every step.
	function seek(delta) {
		var total = duration || video.duration || 0;
		seekTarget = Math.max(0, Math.min((seekTarget != null ? seekTarget : video.currentTime) + delta, total ? total - 5 : Infinity));
		showOsd();
		clearTimeout(timers.seek);
		timers.seek = setTimeout(function () {
			if (inGroup()) SyncPlay.request('Seek', { PositionTicks: Math.round(seekTarget * 1e7) });
			else video.currentTime = seekTarget;
			seekTarget = null;
		}, 600);
	}

	function togglePause() {
		if (inGroup()) SyncPlay.request(video.paused ? 'Unpause' : 'Pause');
		else if (video.paused) video.play();
		else video.pause();
		showOsd();
	}

	/** Tells the group this TV has the frame at the current position and can go. */
	function syncReady() {
		if (!state.syncWaiting || !inGroup()) return;
		state.syncWaiting = false;
		SyncPlay.report('Ready', video.currentTime, !video.paused);
		// The server holds everyone until every member has loaded; say so instead of looking stuck on "Loading…".
		if (video.paused) {
			spinner.textContent = 'Waiting for the others in ' + SyncPlay.group.GroupName + '…';
			spinner.style.display = '';
		}
	}

	video.addEventListener('loadedmetadata', function () {
		if (pendingSeek > 0) video.currentTime = pendingSeek;
		pendingSeek = 0;
	});
	video.addEventListener('canplay', syncReady);
	video.addEventListener('seeked', syncReady);
	video.addEventListener('playing', function () {
		spinner.style.display = 'none';
		spinner.textContent = 'Loading…';
		if (state.reported) return report('/Progress');
		state.reported = true;
		report('');
		timers.progress = setInterval(function () { report('/Progress'); }, 10000);
	});
	video.addEventListener('waiting', function () {
		spinner.style.display = '';
		if (!inGroup() || state.syncWaiting) return;
		state.syncWaiting = true;
		SyncPlay.report('Buffering', video.currentTime, !video.paused);
	});
	video.addEventListener('pause', function () {
		if (!state.reported) return;
		report('/Progress');
		showOsd();
	});
	video.addEventListener('timeupdate', function () { if (el.classList.contains('osd-shown')) updateOsd(); });
	video.addEventListener('error', function () {
		if (!state.source || state.destroyed) return;
		// The TV could not decode the file after all: fall back to the server's transcode.
		if (state.method === 'DirectPlay') return load(video.currentTime || pendingSeek, false);
		UI.toast('Playback error');
		App.back();
	});
	video.addEventListener('ended', function () {
		if (state.destroyed) return;
		stopReporting();
		// In a group the server moves everyone to the next queue entry (the rest of the series); it opens here by itself.
		if (inGroup()) return SyncPlay.request('NextItem', { PlaylistItemId: sync.playlistItemId });
		if (item.Type !== 'Episode' || sync) return App.back();
		Api.episodes(item.SeriesId, { startItemId: item.Id, limit: 2 }).then(function (episodes) {
			var next = episodes[1];
			if (next) App.replace(PlayerScreen(next, 0));
			else App.back();
		}).catch(function () { App.back(); });
	});

	// With the magic remote pointer showing, OK arrives as a click instead of a key.
	el.addEventListener('click', function () {
		if (!App.modal && Date.now() - lastEnterKey > 400) togglePause();
	});

	function onVisibility() { if (document.hidden && !inGroup()) video.pause(); }
	document.addEventListener('visibilitychange', onVisibility);

	if (sync) {
		SyncPlay.player = {
			playlistItemId: sync.playlistItemId,
			/** A group command, due at local time [when] (ms). */
			command: function (type, position, when) {
				clearTimeout(timers.command);
				var delay = when - Date.now();
				if (type === 'Unpause') {
					// Late: start further in, where the others already are.
					var target = position + Math.max(0, -delay) / 1000;
					if (Math.abs(video.currentTime - target) > 0.5) video.currentTime = target;
					timers.command = setTimeout(function () { video.play(); }, Math.max(0, delay));
				} else if (type === 'Pause') {
					timers.command = setTimeout(function () {
						video.pause();
						if (Math.abs(video.currentTime - position) > 0.5) video.currentTime = position;
					}, Math.max(0, delay));
				} else if (type === 'Seek') {
					video.pause();
					state.syncWaiting = true;
					video.currentTime = position;
				} else if (type === 'Stop') {
					App.back();
				}
				showOsd();
			}
		};
	}

	return {
		el: el,
		isPlayer: true,
		onShow: function (first) {
			Backdrop.show(null);
			if (first) load(startSeconds || 0, true);
		},
		onKey: function (e) {
			switch (e.keyCode) {
				case Keys.ENTER: case Keys.PLAY: case Keys.PAUSE: togglePause(); return true;
				case Keys.LEFT: case Keys.RW: seek(-SEEK_BACK); return true;
				case Keys.RIGHT: case Keys.FF: seek(SEEK_FORWARD); return true;
				case Keys.UP: trackMenu(); return true;
				case Keys.DOWN: showOsd(); return true;
				case Keys.STOP: App.back(); return true;
				default: return false;
			}
		},
		onBack: function () {
			if (!el.classList.contains('osd-shown') || video.paused) return false;
			el.classList.remove('osd-shown');
			return true;
		},
		destroy: function () {
			state.destroyed = true;
			if (sync && SyncPlay.player && SyncPlay.player.playlistItemId === sync.playlistItemId) SyncPlay.player = null;
			stopReporting();
			Object.keys(timers).forEach(function (key) {
				clearTimeout(timers[key]);
				clearInterval(timers[key]);
			});
			document.removeEventListener('visibilitychange', onVisibility);
			video.pause();
			video.removeAttribute('src');
			video.load();
		}
	};
}
