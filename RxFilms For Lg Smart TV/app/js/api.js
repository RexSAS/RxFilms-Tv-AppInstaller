// Jellyfin and Seerr HTTP clients.
var VERSION = '1.0.0';
var Config = window.RXFILMS_CONFIG || {};

function query(params) {
	var parts = [];
	Object.keys(params || {}).forEach(function (key) {
		var value = params[key];
		if (value == null) return;
		if (Array.isArray(value)) value = value.join(',');
		parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
	});
	return parts.length ? '?' + parts.join('&') : '';
}

// Pending Luna bridges are kept here; a collected bridge never calls back.
var lunaCalls = [];

function luna(uri, params) {
	return new Promise(function (resolve, reject) {
		var bridge = new PalmServiceBridge();
		lunaCalls.push(bridge);
		bridge.onservicecallback = function (message) {
			lunaCalls.splice(lunaCalls.indexOf(bridge), 1);
			var result = JSON.parse(message);
			if (result.returnValue) resolve(result);
			else reject(new Error(result.errorText || uri + ' failed'));
		};
		bridge.call(uri, JSON.stringify(params));
	});
}

function store(key, value) {
	if (value) localStorage.setItem(key, value);
	else localStorage.removeItem(key);
}

// The profile is picked at every launch, so no session survives a restart (old single-session keys are dropped).
store('token', '');
store('userId', '');

var Api = {
	server: localStorage.getItem('server') || '',
	token: '',
	userId: '',
	deviceId: localStorage.getItem('deviceId') || '',

	FIELDS: 'Overview,Genres,PrimaryImageAspectRatio',

	setServer: function (url) {
		if (url !== this.server) store('tokens', '');
		this.server = url;
		store('server', url);
	},

	// Access tokens per user id, so picking a profile signs in without the password; the password itself is never kept.
	savedTokens: function () {
		try {
			return JSON.parse(localStorage.getItem('tokens')) || {};
		} catch (e) {
			return {};
		}
	},
	setSession: function (token, userId) {
		var tokens = this.savedTokens();
		if (token) tokens[userId] = token;
		else delete tokens[this.userId];
		store('tokens', JSON.stringify(tokens));
		this.token = token;
		this.userId = userId;
	},

	authHeader: function () {
		if (!this.deviceId) {
			this.deviceId = 'rxfilms-webos-' + Date.now().toString(36) + Math.random().toString(36).slice(2);
			store('deviceId', this.deviceId);
		}
		return 'MediaBrowser Client="RxFilms", Device="LG webOS TV", DeviceId="' + this.deviceId + '", Version="' + VERSION + '"' +
			(this.token ? ', Token="' + this.token + '"' : '');
	},

	request: function (method, path, params, body, server) {
		var self = this;
		return fetch((server || this.server) + path + query(params), {
			method: method,
			headers: { 'Authorization': this.authHeader(), 'Content-Type': 'application/json' },
			body: body ? JSON.stringify(body) : undefined
		}).then(function (response) {
			// Token revoked or expired: back to sign in.
			if (response.status === 401 && self.token && window.App) App.signedOut();
			if (!response.ok) throw new Error(method + ' ' + path + ' failed: ' + response.status);
			return response.text().then(function (text) { return text ? JSON.parse(text) : null; });
		});
	},
	get: function (path, params) { return this.request('GET', path, params); },
	post: function (path, body, params) { return this.request('POST', path, params, body || {}); },
	del: function (path, params) { return this.request('DELETE', path, params); },

	image: function (id, type, params) {
		return this.server + '/Items/' + id + '/Images/' + type + query(Object.assign({ quality: 90 }, params));
	},

	items: function (params) {
		return this.get('/Items', Object.assign({ userId: this.userId, fields: this.FIELDS, enableTotalRecordCount: false }, params))
			.then(function (r) { return r.Items; });
	},
	item: function (id) { return this.get('/Items/' + id, { userId: this.userId }); },
	views: function () { return this.get('/UserViews', { userId: this.userId }).then(function (r) { return r.Items; }); },
	resume: function () {
		return this.get('/UserItems/Resume', { userId: this.userId, limit: 30, mediaTypes: 'Video', fields: this.FIELDS })
			.then(function (r) { return r.Items; });
	},
	nextUp: function (seriesId) {
		return this.get('/Shows/NextUp', { userId: this.userId, limit: 30, fields: this.FIELDS, seriesId: seriesId, enableResumable: false })
			.then(function (r) { return r.Items; });
	},
	latest: function (parentId) {
		return this.get('/Items/Latest', { userId: this.userId, parentId: parentId, limit: 30, fields: this.FIELDS });
	},
	similar: function (id) {
		return this.get('/Items/' + id + '/Similar', { userId: this.userId, limit: 20, fields: this.FIELDS })
			.then(function (r) { return r.Items; });
	},
	seasons: function (seriesId) {
		return this.get('/Shows/' + seriesId + '/Seasons', { userId: this.userId }).then(function (r) { return r.Items; });
	},
	episodes: function (seriesId, params) {
		return this.get('/Shows/' + seriesId + '/Episodes', Object.assign({ userId: this.userId, fields: 'Overview' }, params))
			.then(function (r) { return r.Items; });
	},
	setFavorite: function (id, favorite) {
		return this.request(favorite ? 'POST' : 'DELETE', '/UserFavoriteItems/' + id, { userId: this.userId });
	},
	setPlayed: function (id, played) {
		return this.request(played ? 'POST' : 'DELETE', '/UserPlayedItems/' + id, { userId: this.userId });
	}
};

/**
 * Minimal Seerr (Jellyseerr/Overseerr) client, same as the Fire TV app's Seerr.kt.
 * shortcut: server and API key are build-time settings (rxfilms.seerr.url / rxfilms.seerr.key) and requests are made
 * as the key's user; move to a settings screen if other people use the app.
 */
var Seerr = {
	enabled: !!(Config.seerrUrl && Config.seerrKey),
	TRENDING_MAX_PAGES: 5,

	// Seerr sends no CORS headers, so on the TV the bundled service (service/service.js) makes the call.
	// A plain fetch is kept for desktop browsers started with web security off.
	call: function (method, path, body) {
		var request = {
			url: Config.seerrUrl + path,
			method: method,
			headers: { 'X-Api-Key': Config.seerrKey, 'Content-Type': 'application/json' },
			body: body ? JSON.stringify(body) : undefined
		};
		var response = window.PalmServiceBridge ? luna('luna://org.rxfilms.tv.service/request', request) : fetch(request.url, request).then(function (r) {
			return r.text().then(function (text) { return { status: r.status, body: text }; });
		});
		return response.then(function (r) {
			if (r.status < 200 || r.status > 299) throw new Error('Seerr ' + method + ' ' + path + ' failed: ' + r.status);
			return JSON.parse(r.body);
		});
	},
	titles: function (path) {
		if (!this.enabled) return Promise.resolve([]);
		return this.call('GET', path).then(Logic.seerrTitles);
	},

	discoverMovies: function () { return this.titles('/api/v1/discover/movies'); },
	discoverSeries: function () { return this.titles('/api/v1/discover/tv'); },

	trending: function (movies, count) {
		count = count || 10;
		var self = this;
		var pages = [];
		function next(page) {
			return self.titles('/api/v1/discover/trending?page=' + page).then(function (titles) {
				pages.push(titles);
				var top = Logic.topTen(pages, movies, count);
				return top.length >= count || page >= self.TRENDING_MAX_PAGES || !titles.length ? top : next(page + 1);
			});
		}
		return next(1);
	},

	// Seerr rejects "+" for spaces; encodeURIComponent already writes %20.
	search: function (text) { return this.titles('/api/v1/search?query=' + encodeURIComponent(text)); },

	request: function (title) {
		var body = { mediaType: title.mediaType, mediaId: title.id };
		if (title.mediaType !== 'movie') body.seasons = 'all';
		return this.call('POST', '/api/v1/request', body);
	}
};
