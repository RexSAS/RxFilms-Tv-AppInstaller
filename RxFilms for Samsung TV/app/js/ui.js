// Shared building blocks: cards, rows, backdrop, toast, dialogs.
function h(tag, cls, text) {
	var el = document.createElement(tag);
	if (cls) el.className = cls;
	if (text != null) el.textContent = text;
	return el;
}

function focusable(el, select) {
	el.classList.add('focusable');
	el._select = select;
	return el;
}

// Images load when they get near the screen, so a home screen with hundreds of posters stays within TV memory.
var lazyObserver = window.IntersectionObserver ? new IntersectionObserver(function (entries) {
	entries.forEach(function (entry) {
		if (!entry.isIntersecting) return;
		entry.target.style.backgroundImage = 'url("' + entry.target._src + '")';
		lazyObserver.unobserve(entry.target);
	});
}, { rootMargin: '700px' }) : null;

function lazyImage(el, url) {
	if (!url) return;
	if (!lazyObserver) {
		el.style.backgroundImage = 'url("' + url + '")';
		return;
	}
	el._src = url;
	lazyObserver.observe(el);
}

var UI = {
	posterUrl: function (item) {
		var tags = item.ImageTags || {};
		if (tags.Primary) return Api.image(item.Id, 'Primary', { fillWidth: 220, fillHeight: 330, tag: tags.Primary });
		if (item.SeriesPrimaryImageTag) return Api.image(item.SeriesId, 'Primary', { fillWidth: 220, fillHeight: 330, tag: item.SeriesPrimaryImageTag });
		return null;
	},

	wideUrl: function (item) {
		var tags = item.ImageTags || {};
		var size = { fillWidth: 400, fillHeight: 225 };
		if (item.Type === 'Episode' && tags.Primary) return Api.image(item.Id, 'Primary', Object.assign({ tag: tags.Primary }, size));
		if (tags.Thumb) return Api.image(item.Id, 'Thumb', Object.assign({ tag: tags.Thumb }, size));
		if (item.BackdropImageTags && item.BackdropImageTags.length) return Api.image(item.Id, 'Backdrop', Object.assign({ tag: item.BackdropImageTags[0] }, size));
		if (item.ParentThumbImageTag) return Api.image(item.ParentThumbItemId, 'Thumb', Object.assign({ tag: item.ParentThumbImageTag }, size));
		if (item.ParentBackdropImageTags && item.ParentBackdropImageTags.length) return Api.image(item.ParentBackdropItemId, 'Backdrop', Object.assign({ tag: item.ParentBackdropImageTags[0] }, size));
		return tags.Primary ? Api.image(item.Id, 'Primary', Object.assign({ tag: tags.Primary }, size)) : null;
	},

	// Shown dimmed behind the rows, so 1280px is plenty and decodes much faster on the TV.
	backdropUrl: function (item) {
		if (item.BackdropImageTags && item.BackdropImageTags.length) return Api.image(item.Id, 'Backdrop', { maxWidth: 1280, tag: item.BackdropImageTags[0] });
		if (item.ParentBackdropImageTags && item.ParentBackdropImageTags.length) return Api.image(item.ParentBackdropItemId, 'Backdrop', { maxWidth: 1280, tag: item.ParentBackdropImageTags[0] });
		return null;
	},

	/** Next up: the season's (or else the show's) artwork instead of a frame from the episode. */
	seasonArtUrl: function (episode) {
		var size = { fillWidth: 400, fillHeight: 225 };
		if (episode.ParentThumbImageTag) return Api.image(episode.ParentThumbItemId, 'Thumb', Object.assign({ tag: episode.ParentThumbImageTag }, size));
		if (episode.ParentBackdropImageTags && episode.ParentBackdropImageTags.length) {
			return Api.image(episode.ParentBackdropItemId, 'Backdrop', Object.assign({ tag: episode.ParentBackdropImageTags[0] }, size));
		}
		return UI.wideUrl(episode);
	},

	episodeLabel: function (item) {
		var number = item.ParentIndexNumber != null && item.IndexNumber != null ? 'S' + item.ParentIndexNumber + ':E' + item.IndexNumber + ' · ' : '';
		return number + item.Name;
	},

	decorate: function (img, item) {
		// No background, like the Jellyfin Enhanced rating tag; the shadow keeps it readable on light posters.
		if (item.CommunityRating > 0) img.appendChild(h('div', 'rating', '★ ' + item.CommunityRating.toFixed(1)));
		var played = item.UserData && item.UserData.PlayedPercentage;
		if (played > 0 && played < 100) {
			var bar = h('div', 'progress');
			var fill = h('div');
			fill.style.width = played + '%';
			bar.appendChild(fill);
			img.appendChild(bar);
		}
	},

	itemCard: function (item, wide, onSelect, imageUrl) {
		var card = focusable(h('div', wide ? 'card wide' : 'card'), onSelect || function () { App.openItem(item); });
		var img = h('div', 'img');
		lazyImage(img, imageUrl || (wide ? UI.wideUrl(item) : UI.posterUrl(item)));
		UI.decorate(img, item);
		card.appendChild(img);
		card.appendChild(h('div', 'title', item.Type === 'Episode' ? item.SeriesName : item.Name));
		if (wide && item.Type === 'Episode') card.appendChild(h('div', 'subtitle', UI.episodeLabel(item)));
		card._focus = function () { Backdrop.set(UI.backdropUrl(item)); };
		return card;
	},
	posterCard: function (item) { return UI.itemCard(item, false); },
	wideCard: function (item) { return UI.itemCard(item, true); },
	nextUpCard: function (episode) { return UI.itemCard(episode, true, null, UI.seasonArtUrl(episode)); },

	/** Back and home buttons in the top left of a page. */
	pageBar: function (screenEl) {
		var bar = h('div', 'page-bar nav-explicit');
		var back = focusable(h('div', 'toolbar-button icon'), function () { App.back(); });
		var home = focusable(h('div', 'toolbar-button icon'), function () { App.home(); });
		back.innerHTML = ICONS.back;
		home.innerHTML = ICONS.home;
		bar.appendChild(back);
		bar.appendChild(home);
		screenEl.appendChild(bar);
	},

	seerrCard: function (title, onSelect) {
		var card = focusable(h('div', 'card'), function () { onSelect(title); });
		var img = h('div', 'img');
		lazyImage(img, title.posterUrl);
		if (title.rank) {
			img.appendChild(h('div', 'rank-shade'));
			img.appendChild(h('div', 'rank rank-' + Math.min(title.rank, 4), String(title.rank)));
		}
		card.appendChild(img);
		card.appendChild(h('div', 'title', title.title + (title.year ? ' (' + title.year + ')' : '')));
		card.appendChild(h('div', 'status', Logic.seerrStatusLabel(title.status)));
		card._focus = function () { Backdrop.set(null); };
		return card;
	},

	row: function (title) {
		var el = h('section', 'row');
		var track = h('div', 'track-x');
		var viewport = h('div', 'row-viewport');
		el.appendChild(h('h2', null, title));
		viewport.appendChild(track);
		el.appendChild(viewport);
		return {
			el: el,
			track: track,
			set: function (cards) {
				track.innerHTML = '';
				track.setAttribute('data-off', 0);
				track.style.transform = '';
				cards.forEach(function (card) { track.appendChild(card); });
			}
		};
	},

	/** A row filled by load(); hidden while loading and left out when it fails or comes back empty. */
	asyncRow: function (container, title, load, toCard, before) {
		var row = UI.row(title);
		row.el.style.display = 'none';
		container.insertBefore(row.el, before || null);
		row.reload = function () {
			var focusIndex = Array.prototype.indexOf.call(row.track.children, Nav.current);
			return load().then(function (items) {
				row.set(items.map(toCard));
				row.el.style.display = items.length ? '' : 'none';
				if (focusIndex >= 0 && items.length) Nav.focus(row.track.children[Math.min(focusIndex, items.length - 1)]);
			}).catch(function (error) {
				console.warn('Unable to load ' + title, error);
				row.el.style.display = 'none';
			});
		};
		row.reload();
		return row;
	},

	toast: function (text) {
		var toast = document.getElementById('toast');
		toast.textContent = text;
		toast.classList.add('shown');
		clearTimeout(UI.toastTimer);
		UI.toastTimer = setTimeout(function () { toast.classList.remove('shown'); }, 3500);
	},

	/** Modal list of choices; [options] are {label, checked, action}. */
	menu: function (title, options) {
		var overlay = h('div', 'modal');
		var box = h('div', 'modal-box');
		var viewport = h('div', 'modal-list');
		var list = h('div', 'track-y');
		list.setAttribute('data-anchor', 300);
		box.appendChild(h('h2', null, title));
		viewport.appendChild(list);
		box.appendChild(viewport);
		options.forEach(function (option) {
			var button = focusable(h('div', 'menu-item' + (option.checked ? ' checked' : ''), option.label), function () {
				App.closeModal();
				if (option.action) option.action();
			});
			if (option.checked) button.setAttribute('data-autofocus', '');
			list.appendChild(button);
		});
		overlay.appendChild(box);
		App.openModal(overlay);
	},

	confirm: function (text, okLabel, action) {
		UI.menu(text, [{ label: okLabel, action: action }, { label: 'Cancel' }]);
	}
};

/** Full-screen backdrop of the focused title, cross-faded between two layers. */
var Backdrop = {
	url: null,
	front: 0,
	set: function (url) {
		clearTimeout(this.timer);
		var self = this;
		this.timer = setTimeout(function () { self.show(url); }, 300);
	},
	show: function (url) {
		if (url === this.url) return;
		this.url = url;
		var layers = document.querySelectorAll('#backdrop .layer');
		this.front = 1 - this.front;
		layers[1 - this.front].classList.remove('shown');
		if (!url) return;
		var layer = layers[this.front];
		layer.style.backgroundImage = 'url("' + url + '")';
		layer.classList.add('shown');
	}
};
