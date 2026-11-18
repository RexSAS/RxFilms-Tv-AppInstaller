// Home: toolbar, rotating banner and the same rows, in the same order, as the Fire TV app.
var ICONS = {
	home: '<svg viewBox="0 0 24 24"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg>',
	search: '<svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>',
	back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>',
	users: '<svg viewBox="0 0 24 24"><path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5s-3 1.34-3 3 1.34 3 3 3m-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5 5 6.34 5 8s1.34 3 3 3m0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5m8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5"/></svg>'
};

// Copied from the Fire TV app (HomeFragmentFranchisesRow.kt); pad is its logo padding scaled to these cards.
var FRANCHISES = [
	{ name: 'Marvel', tags: ['Marvel Entertainment', 'Marvel Studios'], colors: ['#1a0a0a', '#7a1f1f', '#0a0a0a'], pad: 43,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/mIkZDuulwMPzESbzF9lg3rD8CcO.png' },
	{ name: '20th Century Studios', tags: ['20th Century Studios'], colors: ['#0a0a1a', '#8a6b00', '#0d0906'], pad: 25,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/h0rjX5vjW5r8yEnUBStFarjcLT4.png' },
	{ name: 'DC Universe', tags: ['DC Films', 'DC'], colors: ['#071226', '#0f4f8b', '#13040b'], pad: 25,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/2Tc1P3Ac8M479naPp1kYT3izLS5.png' },
	{ name: 'Disney', tags: ['Walt Disney Productions', 'Walt Disney Pictures', 'Disney Television Animation'], colors: ['#071426', '#0f5f9b', '#07101c'], pad: 14,
		logo: 'https://i.imgur.com/cPpeEuL.png' },
	{ name: 'Columbia Pictures', tags: ['Columbia Pictures'], colors: ['#1b0d10', '#7b1f2e', '#090909'], pad: 43, invert: true,
		logo: 'https://i.imgur.com/9XhT64p.png' },
	{ name: 'Warner Bros. Pictures', tags: ['Warner Bros. Pictures'], colors: ['#0a1018', '#c28a1f', '#0a0a0a'], pad: 25,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/ky0xOc5OrhzkZ1N6KyUxacfQsCk.png' },
	{ name: 'Universal', tags: ['Universal Pictures'], colors: ['#07121f', '#2f4fa3', '#04070f'], pad: 25,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/8lvHyhjr8oUKOOy2dKXoALWKdp0.png' },
	{ name: 'Dreamworks', tags: ['DreamWorks Animation', 'DreamWorks'], colors: ['#07141c', '#0f7a8a', '#020b12'], pad: 25,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/kP7t6RwGz2AvvTkvnI1uteEwHet.png' },
	{ name: 'Paramount', tags: ['Paramount Pictures', 'Paramount'], colors: ['#0a0f1f', '#4f5d8d', '#070b16'], pad: 25,
		logo: 'https://image.tmdb.org/t/p/w780_filter(duotone,ffffff,bababa)/fycMZt242LVjagMByZOLUGbCvv3.png' }
];

// Libraries the Fire TV toolbar also shows; music, live TV and books have no screen here.
var SUPPORTED_VIEWS = ['movies', 'tvshows', 'boxsets', 'homevideos', 'mixed', undefined];

/** Seerr title: open it when it is on the server, otherwise search for it (requesting happens from search). */
function openSeerrTitle(title) {
	var id = Logic.seerrAvailableId(title);
	App.push(id ? DetailsScreen(id) : SearchScreen(title.title));
}

function HomeScreen() {
	var el = h('div', 'screen home');
	var track = h('div', 'track-y');
	track.setAttribute('data-anchor', 150);
	el.appendChild(track);

	var hero = MediaBar(function () { Nav.focus(homeButton); });
	track.appendChild(hero.el);
	// Down from the banner starts the first row at its first card, not at the card under the screen's center.
	hero.el._nav.down = function () {
		var firstRow = Array.prototype.filter.call(track.querySelectorAll('.row'), function (row) {
			return row !== hero.el && visible(row) && row.querySelector('.focusable');
		})[0];
		return firstRow ? Nav.focus(firstRow.querySelector('.focusable')) : false;
	};

	// Toolbar: user and home | libraries | search and clock.
	var toolbar = h('div', 'toolbar nav-explicit');
	var start = h('div', 'toolbar-group');
	var center = h('div', 'toolbar-group');
	var end = h('div', 'toolbar-group');
	var clock = h('div', 'clock');
	function toolbarButton(parent, cls, label, action) {
		var button = focusable(h('div', 'toolbar-button ' + cls, label), action);
		// The banner sits under the toolbar, so "down" has to be pointed at it.
		button._nav = { down: function () { return hero.el.parentNode ? Nav.focus(hero.el) : false; } };
		parent.appendChild(button);
		return button;
	}
	var userButton = toolbarButton(start, 'icon user-button', '', function () {
		UI.menu(App.userName, [
			{ label: 'Switch user', action: App.switchUser.bind(App) },
			{ label: 'Sign out', action: App.signOut }
		]);
	});
	var homeButton = toolbarButton(start, 'icon', null, function () { Nav.focus(hero.el.parentNode ? hero.el : track.querySelector('.focusable')); });
	homeButton.innerHTML = ICONS.home;
	toolbarButton(end, 'icon', null, function () { App.push(SearchScreen()); }).innerHTML = ICONS.search;
	toolbarButton(end, 'icon', null, function () { SyncPlay.menu(); }).innerHTML = ICONS.users;
	end.appendChild(clock);
	SyncPlay.connect();
	toolbar.appendChild(start);
	toolbar.appendChild(center);
	toolbar.appendChild(end);
	el.appendChild(toolbar);

	Api.get('/Users/' + Api.userId).then(function (user) {
		App.userName = user.Name;
		if (user.PrimaryImageTag) userButton.style.backgroundImage = 'url("' + Api.server + '/Users/' + user.Id + '/Images/Primary?tag=' + user.PrimaryImageTag + '")';
		else userButton.textContent = user.Name.charAt(0).toUpperCase();
	}).catch(function () {});

	function tick() {
		var now = new Date();
		clock.textContent = now.getHours() + ':' + (now.getMinutes() < 10 ? '0' : '') + now.getMinutes();
	}
	tick();
	var clockTimer = setInterval(tick, 10000);

	// Rows load in parallel; each gets a slot first so they keep this order whatever finishes first.
	function slot() {
		var s = h('div');
		track.appendChild(s);
		return s;
	}
	var resumeRow = UI.asyncRow(track, 'Continue watching', function () { return Api.resume(); }, UI.wideCard);
	var nextUpRow = UI.asyncRow(track, 'Next up', function () { return Api.nextUp(); }, UI.nextUpCard);
	if (Seerr.enabled) {
		var seerrCard = function (title) { return UI.seerrCard(title, openSeerrTitle); };
		UI.asyncRow(track, 'Top 10 Movies', function () { return Seerr.trending(true); }, seerrCard);
		UI.asyncRow(track, 'Top 10 Series', function () { return Seerr.trending(false); }, seerrCard);
		UI.asyncRow(track, 'Downloadable Movies', function () { return Seerr.discoverMovies(); }, seerrCard);
		UI.asyncRow(track, 'Downloadable Series', function () { return Seerr.discoverSeries(); }, seerrCard);
	}
	becauseYouWatched(slot(), 'Movie');
	becauseYouWatched(slot(), 'Episode');
	var latestSlot = slot();
	Api.views().then(function (list) {
		list.forEach(function (view) {
			if (SUPPORTED_VIEWS.indexOf(view.CollectionType) < 0) return;
			toolbarButton(center, '', view.Name, function () { App.push(LibraryScreen(view)); });
			if (view.CollectionType === 'movies' || view.CollectionType === 'tvshows') {
				UI.asyncRow(latestSlot, 'Recently added in ' + view.Name, function () { return Api.latest(view.Id); }, UI.posterCard);
			}
		});
	}).catch(function (error) { console.warn('Unable to load libraries', error); });
	FranchisesRow(slot());
	genres(slot());

	function becauseYouWatched(container, type) {
		Api.items({ includeItemTypes: type, isPlayed: true, sortBy: 'DatePlayed', sortOrder: 'Descending', recursive: true, limit: 1 }).then(function (items) {
			var watched = items[0];
			if (!watched) return;
			var id = type === 'Episode' ? watched.SeriesId : watched.Id;
			var name = type === 'Episode' ? watched.SeriesName || watched.Name : watched.Name;
			UI.asyncRow(container, 'Because you watched ' + name, function () { return Api.similar(id); }, UI.posterCard);
		}).catch(function (error) { console.warn('Unable to load because you watched', error); });
	}

	// A few random genres, so home changes between visits.
	function genres(container) {
		var types = 'Movie,Series';
		Api.get('/Genres', { userId: Api.userId, includeItemTypes: types, recursive: true }).then(function (result) {
			var list = result.Items.slice();
			for (var i = list.length - 1; i > 0; i--) {
				var j = Math.floor(Math.random() * (i + 1));
				var swap = list[i];
				list[i] = list[j];
				list[j] = swap;
			}
			list.slice(0, 3).forEach(function (genre) {
				UI.asyncRow(container, genre.Name, function () {
					return Api.items({ genreIds: genre.Id, includeItemTypes: types, recursive: true, sortBy: 'Random', limit: 30, imageTypeLimit: 1 });
				}, UI.posterCard);
			});
		}).catch(function (error) { console.warn('Unable to load genres', error); });
	}

	// Banner text fades out once focus moves down into the rows, where it would sit under the toolbar.
	el.addEventListener('navfocus', function (e) {
		el.classList.toggle('in-rows', !hero.el.contains(e.target) && !toolbar.contains(e.target));
	});

	return {
		el: el,
		onShow: function (first) {
			hero.resume();
			if (first) return;
			// Back from playback: progress changed.
			resumeRow.reload();
			nextUpRow.reload();
		},
		onHide: function () { hero.pause(); },
		onBack: function () {
			// Back scrolls up to the banner first, then exits.
			if (!hero.el.parentNode || Nav.current === hero.el || toolbar.contains(Nav.current)) return false;
			Nav.focus(hero.el);
			return true;
		},
		destroy: function () {
			hero.pause();
			clearInterval(clockTimer);
		}
	};
}

/** Rotating banner of random titles, like the Media Bar web plugin. Left/right flip, OK opens. */
function MediaBar(onUp) {
	var ROTATE_MILLIS = 12000;
	var items = [];
	var index = 0;
	var front = 0;
	var timer = null;
	var paused = false;

	var el = focusable(h('div', 'row hero'), function () { if (items.length) App.push(DetailsScreen(items[index].Id)); });
	var fadeX = h('div', 'hero-fade-x');
	var fadeY = h('div', 'hero-fade-y');
	var layers = [h('div', 'hero-image'), h('div', 'hero-image')];
	var info = h('div', 'hero-info');
	var dots = h('div', 'hero-dots');
	layers.forEach(function (layer) { fadeY.appendChild(layer); });
	fadeY.appendChild(h('div', 'hero-scrim'));
	fadeX.appendChild(fadeY);
	el.appendChild(fadeX);
	el.appendChild(info);
	el.appendChild(dots);

	function show(i) {
		index = (i + items.length) % items.length;
		var item = items[index];
		var url = Api.image(item.Id, 'Backdrop', { maxWidth: 1920, tag: item.BackdropImageTags[0] });
		var shown = index;
		// Cross-fade only once the next image has loaded, so it never fades to an empty frame.
		var image = new Image();
		image.onload = image.onerror = function () {
			if (shown !== index) return;
			front = 1 - front;
			layers[front].style.backgroundImage = 'url("' + url + '")';
			layers[front].classList.add('shown');
			layers[1 - front].classList.remove('shown');
		};
		image.src = url;

		info.innerHTML = '';
		var logoTag = item.ImageTags && item.ImageTags.Logo;
		if (logoTag) {
			var logo = h('img', 'hero-logo');
			logo.src = Api.image(item.Id, 'Logo', { maxWidth: 800, tag: logoTag });
			info.appendChild(logo);
		} else {
			info.appendChild(h('div', 'hero-title', item.Name));
		}
		var meta = [];
		if (item.ProductionYear) meta.push(item.ProductionYear);
		if (item.CommunityRating) meta.push('★ ' + item.CommunityRating.toFixed(1));
		if (item.Genres && item.Genres.length) meta.push(item.Genres.slice(0, 3).join(', '));
		info.appendChild(h('div', 'hero-meta', meta.join('  ·  ')));
		info.appendChild(h('div', 'hero-overview', item.Overview || ''));
		info.appendChild(h('div', 'pill', 'More info'));

		Array.prototype.forEach.call(dots.children, function (dot, d) { dot.classList.toggle('active', d === index); });
		schedule();
	}

	function schedule() {
		clearTimeout(timer);
		if (!paused) timer = setTimeout(function () { show(index + 1); }, ROTATE_MILLIS);
	}

	el._nav = {
		left: function () { show(index - 1); },
		right: function () { show(index + 1); },
		up: onUp
	};
	el._focus = function () { Backdrop.set(null); };

	Api.items({
		includeItemTypes: 'Movie,Series',
		recursive: true,
		sortBy: 'Random',
		imageTypes: 'Backdrop',
		fields: 'Overview,Genres',
		enableImageTypes: 'Backdrop,Logo',
		imageTypeLimit: 1,
		limit: 10
	}).then(function (result) {
		items = result;
		if (!items.length) throw new Error('No titles with a backdrop');
		items.forEach(function () { dots.appendChild(h('div', 'dot')); });
		show(0);
	}).catch(function (error) {
		console.warn('Unable to load the banner', error);
		var focused = Nav.current === el;
		el.parentNode.removeChild(el);
		if (focused) Nav.focusFirst();
	});

	return {
		el: el,
		pause: function () {
			paused = true;
			clearTimeout(timer);
		},
		resume: function () {
			paused = false;
			if (items.length) schedule();
		}
	};
}

/** Logo cards per franchise; selecting one opens a row with its titles, newest first, right below. Again closes it. */
function FranchisesRow(container) {
	var row = UI.row('Franchises');
	var open = null;
	var openRow = null;
	container.appendChild(row.el);

	row.set(FRANCHISES.map(function (franchise) {
		var card = focusable(h('div', 'franchise'), function () { toggle(franchise); });
		card.style.backgroundImage = 'linear-gradient(135deg, ' + franchise.colors.join(', ') + ')';
		var logo = h('img', franchise.invert ? 'invert' : '');
		logo.src = franchise.logo;
		logo.alt = franchise.name;
		logo.style.padding = franchise.pad + 'px';
		card.appendChild(logo);
		card._focus = function () { Backdrop.set(null); };
		return card;
	}));

	function toggle(franchise) {
		if (openRow) container.removeChild(openRow.el);
		openRow = null;
		if (open === franchise) {
			open = null;
			return;
		}
		open = franchise;
		// The server splits studio names on "|", so filter by studio id instead.
		Promise.all(franchise.tags.map(function (tag) {
			return Api.get('/Studios/' + encodeURIComponent(tag)).then(function (studio) { return studio.Id; }).catch(function () { return null; });
		})).then(function (ids) {
			ids = ids.filter(Boolean);
			if (open !== franchise || !ids.length) return;
			// shortcut: first 100 titles only, page like the library grid if a franchise outgrows that.
			openRow = UI.asyncRow(container, franchise.name, function () {
				return Api.items({ studioIds: ids, includeItemTypes: 'Movie,Series', recursive: true, sortBy: 'PremiereDate', sortOrder: 'Descending', limit: 100, imageTypeLimit: 1 });
			}, UI.posterCard, row.el.nextSibling);
		});
	}
}
