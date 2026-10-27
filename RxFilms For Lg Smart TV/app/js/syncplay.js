// SyncPlay: watch together with other Jellyfin apps. In a group the server decides play, pause and seek for everyone;
// this TV asks for them (SyncPlay.request) and follows the commands that come back over the web socket.
var SyncPlay = {
	group: null,
	player: null,
	socket: null,
	offset: 0, // server clock minus this TV's clock, in ms

	connect: function () {
		if (this.socket) return;
		var self = this;
		var socket = new WebSocket(Api.server.replace(/^http/, 'ws') + '/socket?ApiKey=' + Api.token + '&deviceId=' + encodeURIComponent(Api.deviceId));
		this.socket = socket;
		socket.onmessage = function (e) { self.onMessage(JSON.parse(e.data)); };
		socket.onclose = function () {
			if (self.socket !== socket) return;
			self.socket = null;
			clearInterval(self.keepAlive);
			setTimeout(function () { if (Api.token && !self.socket) self.connect(); }, 5000);
		};
		Api.post('/Sessions/Capabilities/Full', { PlayableMediaTypes: ['Video'], SupportedCommands: [], SupportsMediaControl: false }).catch(function () {});
		clearInterval(this.clockTimer);
		this.clockTimer = setInterval(function () { if (self.group) self.syncClock(); }, 60000);
	},

	disconnect: function () {
		var socket = this.socket;
		this.socket = null;
		this.group = null;
		clearInterval(this.keepAlive);
		clearInterval(this.clockTimer);
		if (socket) socket.close();
	},

	send: function (type) {
		if (this.socket && this.socket.readyState === 1) this.socket.send(JSON.stringify({ MessageType: type }));
	},

	onMessage: function (message) {
		var self = this;
		if (message.MessageType === 'ForceKeepAlive') {
			clearInterval(this.keepAlive);
			this.keepAlive = setInterval(function () { self.send('KeepAlive'); }, message.Data * 500);
			this.send('KeepAlive');
		} else if (message.MessageType === 'SyncPlayGroupUpdate') {
			this.onGroupUpdate(message.Data);
		} else if (message.MessageType === 'SyncPlayCommand') {
			var command = message.Data;
			if (!this.player || command.PlaylistItemId !== this.player.playlistItemId) return;
			this.player.command(command.Command, command.PositionTicks / 1e7, Date.parse(command.When) - this.offset);
		}
	},

	onGroupUpdate: function (update) {
		switch (update.Type) {
			case 'GroupJoined':
				this.group = update.Data;
				this.syncClock();
				UI.toast('SyncPlay: joined ' + update.Data.GroupName);
				break;
			case 'GroupLeft':
			case 'NotInGroup':
				if (this.group) UI.toast('SyncPlay: left the group');
				this.group = null;
				break;
			case 'UserJoined': UI.toast('SyncPlay: ' + update.Data + ' joined'); break;
			case 'UserLeft': UI.toast('SyncPlay: ' + update.Data + ' left'); break;
			case 'GroupDoesNotExist': UI.toast('SyncPlay: that group no longer exists'); break;
			case 'LibraryAccessDenied': UI.toast('SyncPlay: someone in the group cannot open this title'); break;
			case 'PlayQueue': this.onPlayQueue(update.Data); break;
		}
	},

	/** The group's current title changed (or we just joined): open it, paused, at the group's position. */
	onPlayQueue: function (queue) {
		var entry = queue.Playlist && queue.Playlist[queue.PlayingItemIndex];
		if (!entry || (this.player && this.player.playlistItemId === entry.PlaylistItemId)) return;
		Api.item(entry.ItemId).then(function (item) {
			var screen = PlayerScreen(item, queue.StartPositionTicks / 1e7, { playlistItemId: entry.PlaylistItemId });
			if (App.top().isPlayer) App.replace(screen);
			else App.push(screen);
		}).catch(function () { UI.toast('SyncPlay: unable to open the title'); });
	},

	/** Server clock offset from GetUtcTime, assuming the request and the response took equally long. */
	syncClock: function () {
		var self = this;
		var sent = Date.now();
		Api.get('/GetUtcTime').then(function (time) {
			var received = Date.now();
			self.offset = ((Date.parse(time.RequestReceptionTime) - sent) + (Date.parse(time.ResponseTransmissionTime) - received)) / 2;
			self.request('Ping', { Ping: received - sent });
		}).catch(function () {});
	},

	request: function (type, body) {
		return Api.post('/SyncPlay/' + type, body).catch(function (error) {
			console.warn('SyncPlay ' + type + ' failed', error);
			UI.toast('SyncPlay: request failed');
		});
	},

	/** Ready / Buffering for the open title. */
	report: function (type, position, playing) {
		if (!this.group || !this.player) return;
		this.request(type, {
			When: new Date(Date.now() + this.offset).toISOString(),
			PositionTicks: Math.round(position * 1e7),
			IsPlaying: playing,
			PlaylistItemId: this.player.playlistItemId
		});
	},

	setQueue: function (item, startSeconds) {
		this.request('SetNewQueue', { PlayingQueue: [item.Id], PlayingItemPosition: 0, StartPositionTicks: Math.round((startSeconds || 0) * 1e7) });
	},

	menu: function () {
		var self = this;
		if (this.group) {
			return UI.menu('SyncPlay · ' + this.group.GroupName, [
				{ label: 'Leave group', action: function () { self.request('Leave'); } },
				{ label: 'Close' }
			]);
		}
		Api.get('/SyncPlay/List').then(function (groups) {
			var options = groups.map(function (group) {
				return {
					label: 'Join ' + group.GroupName + ' (' + group.Participants.length + ')',
					action: function () { self.request('Join', { GroupId: group.GroupId }); }
				};
			});
			options.push({ label: 'New group', action: function () { self.request('New', { GroupName: (App.userName || 'RxFilms') + "'s group" }); } });
			UI.menu('SyncPlay', options);
		}).catch(function () { UI.toast('SyncPlay is not available for this user'); });
	}
};
