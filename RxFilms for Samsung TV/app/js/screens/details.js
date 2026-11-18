// Title page: info, play/resume, favourite and watched; seasons and episodes for series; similar titles.
function DetailsScreen(id) {
	var el = h('div', 'screen details');
	var track = h('div', 'track-y');
	track.setAttribute('data-anchor', 120);
	el.appendChild(track);
	UI.pageBar(el);

	var item = null;
	var buttons = null;
	var episodesRow = null;

	function button(label, action) { return focusable(h('div', 'button', label), action); }

	function render() {
		track.innerHTML = '';
		Backdrop.show(UI.backdropUrl(item));

		var info = h('section', 'row details-info');
		var isEpisode = item.Type === 'Episode';
		var logoTag = !isEpisode && item.ImageTags && item.ImageTags.Logo;
		if (logoTag) {
			var logo = h('img', 'details-logo');
			logo.src = Api.image(item.Id, 'Logo', { maxWidth: 800, tag: logoTag });
			info.appendChild(logo);
		} else {
			info.appendChild(h('h1', 'details-title', isEpisode ? item.SeriesName : item.Name));
		}
		if (isEpisode) info.appendChild(h('h2', 'details-subtitle', UI.episodeLabel(item)));

		var meta = h('div', 'details-meta');
		if (item.ProductionYear) meta.appendChild(h('span', null, item.ProductionYear));
		if (item.CommunityRating) meta.appendChild(h('span', 'gold', '★ ' + item.CommunityRating.toFixed(1)));
		if (item.RunTimeTicks) meta.appendChild(h('span', null, Math.round(item.RunTimeTicks / 6e8) + ' min'));
		if (item.OfficialRating) meta.appendChild(h('span', 'badge', item.OfficialRating));
		if (item.Genres && item.Genres.length) meta.appendChild(h('span', null, item.Genres.slice(0, 3).join(', ')));
		info.appendChild(meta);
		info.appendChild(h('p', 'details-overview', item.Overview || ''));

		buttons = h('div', 'details-buttons');
		info.appendChild(buttons);
		track.appendChild(info);
		renderButtons();

		if (item.Type === 'Series') renderSeasons();
		if (item.Type === 'Movie' || item.Type === 'Series') {
			UI.asyncRow(track, 'More like this', function () { return Api.similar(item.Id); }, UI.posterCard);
		}
		Nav.focusFirst(buttons);
	}

	function renderButtons() {
		var hadFocus = buttons.contains(Nav.current);
		buttons.innerHTML = '';
		var data = item.UserData || {};
		var position = (data.PlaybackPositionTicks || 0) / 1e7;
		if (item.Type === 'Series') {
			buttons.appendChild(button('Play', playSeries));
		} else if (position > 0) {
			buttons.appendChild(button('Resume from ' + Logic.formatTime(position), function () { App.play(item, position); }));
			buttons.appendChild(button('Play from start', function () { App.play(item, 0); }));
		} else {
			buttons.appendChild(button('Play', function () { App.play(item, 0); }));
		}
		buttons.appendChild(button(data.Played ? '✓ Watched' : 'Mark as watched', function () {
			Api.setPlayed(item.Id, !data.Played).then(refresh);
		}));
		buttons.appendChild(button(data.IsFavorite ? '♥ Favorite' : '♡ Favorite', function () {
			Api.setFavorite(item.Id, !data.IsFavorite).then(refresh);
		}));
		if (item.Type === 'Episode' && item.SeriesId) {
			buttons.appendChild(button('Go to series', function () { App.push(DetailsScreen(item.SeriesId)); }));
		}
		if (hadFocus) Nav.focus(buttons.firstChild);
	}

	// Next up episode, or the very first one.
	function playSeries() {
		Api.nextUp(item.Id).then(function (next) {
			return next.length ? next : Api.episodes(item.Id, { limit: 1 });
		}).then(function (episodes) {
			var episode = episodes[0];
			if (!episode) return UI.toast('No episodes');
			App.play(episode, (episode.UserData && episode.UserData.PlaybackPositionTicks || 0) / 1e7);
		});
	}

	function renderSeasons() {
		var seasonsRow = UI.row('Seasons');
		var seasonsSlot = h('div');
		var episodesSlot = h('div');
		seasonsSlot.appendChild(seasonsRow.el);
		track.appendChild(seasonsSlot);
		track.appendChild(episodesSlot);
		Api.seasons(item.Id).then(function (seasons) {
			if (!seasons.length) return;
			var selected = seasons.filter(function (s) { return s.UserData && s.UserData.UnplayedItemCount > 0 && s.IndexNumber > 0; })[0] || seasons[0];
			seasonsRow.set(seasons.map(function (season) {
				var pill = button(season.Name, function () { selectSeason(season, pill); });
				if (season === selected) setTimeout(function () { selectSeason(season, pill); });
				return pill;
			}));
		});

		function selectSeason(season, pill) {
			Array.prototype.forEach.call(seasonsRow.track.children, function (p) { p.classList.toggle('active', p === pill); });
			if (episodesRow) episodesSlot.removeChild(episodesRow.el);
			episodesRow = UI.asyncRow(episodesSlot, season.Name, function () {
				return Api.episodes(item.Id, { seasonId: season.Id });
			}, function (episode) {
				var card = UI.itemCard(episode, true, function () {
					App.play(episode, (episode.UserData && episode.UserData.PlaybackPositionTicks || 0) / 1e7);
				});
				card.querySelector('.title').textContent = UI.episodeLabel(episode);
				var subtitle = card.querySelector('.subtitle');
				if (subtitle) card.removeChild(subtitle);
				return card;
			});
		}
	}

	function refresh() {
		return Api.item(id).then(function (fresh) {
			item = fresh;
			renderButtons();
		});
	}

	return {
		el: el,
		onShow: function (first) {
			if (first) {
				Api.item(id).then(function (loaded) {
					item = loaded;
					render();
				}).catch(function (error) {
					console.warn(error);
					UI.toast('Unable to load this title');
				});
				return;
			}
			// Back from playback: resume position and episode progress changed.
			if (!item) return;
			Backdrop.show(UI.backdropUrl(item));
			refresh();
			if (episodesRow) episodesRow.reload();
		}
	};
}
