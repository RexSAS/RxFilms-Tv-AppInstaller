// Search the server, plus Seerr titles that can be requested ("Downloadable").
function SearchScreen(initialText) {
	var el = h('div', 'screen search');
	var input = h('input', 'field search-field');
	input.type = 'text';
	input.placeholder = 'Search movies and series';
	input.value = initialText || '';
	focusable(input, function () {
		input.focus();
		window.scrollTo(0, 0);
	});
	input.setAttribute('data-autofocus', '');
	var results = h('div', 'track-y');
	results.setAttribute('data-anchor', 200);
	el.appendChild(results);
	el.appendChild(input);
	UI.pageBar(el);

	var lastText = null;
	function run() {
		var text = input.value.trim();
		if (text === lastText) return;
		lastText = text;
		results.innerHTML = '';
		results.style.transform = '';
		if (!text) return;
		function server(type) {
			return function () { return Api.items({ searchTerm: text, includeItemTypes: type, recursive: true, limit: 30, imageTypeLimit: 1 }); };
		}
		UI.asyncRow(results, 'Movies', server('Movie'), UI.posterCard);
		UI.asyncRow(results, 'Series', server('Series'), UI.posterCard);
		UI.asyncRow(results, 'Episodes', server('Episode'), UI.wideCard);
		if (Seerr.enabled) {
			UI.asyncRow(results, 'Downloadable', function () { return Seerr.search(text); }, function (title) { return UI.seerrCard(title, select); });
		}
	}

	function select(title) {
		var id = Logic.seerrAvailableId(title);
		if (id) return App.push(DetailsScreen(id));
		if (title.status) return UI.toast(title.title + ' is already requested');
		UI.confirm('Request ' + title.title + '?', 'Request', function () {
			Seerr.request(title).then(function () {
				UI.toast('Requested ' + title.title);
			}).catch(function (error) {
				console.warn(error);
				UI.toast('Request failed');
			});
		});
	}

	var typingTimer = null;
	input.addEventListener('input', function () {
		clearTimeout(typingTimer);
		typingTimer = setTimeout(run, 700);
	});
	input._submit = run;

	return {
		el: el,
		onShow: function (first) {
			Backdrop.show(null);
			if (first) run();
		},
		destroy: function () { clearTimeout(typingTimer); }
	};
}
