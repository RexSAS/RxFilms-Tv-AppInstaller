// Poster grid of a library or collection, loaded a page at a time as focus moves down.
function LibraryScreen(parent) {
	var PAGE_SIZE = 70;
	var el = h('div', 'screen library');
	var grid = h('div', 'track-y grid');
	grid.setAttribute('data-anchor', 170);
	el.appendChild(grid);
	el.appendChild(h('h1', 'page-title', parent.Name));
	UI.pageBar(el);

	var types = { movies: 'Movie', tvshows: 'Series' }[parent.CollectionType];
	var params = types
		? { parentId: parent.Id, includeItemTypes: types, recursive: true, sortBy: 'SortName', imageTypeLimit: 1 }
		: { parentId: parent.Id, sortBy: 'IsFolder,SortName', imageTypeLimit: 1 };
	var loaded = 0;
	var loading = false;
	var done = false;

	function more() {
		if (loading || done) return;
		loading = true;
		Api.items(Object.assign({ startIndex: loaded, limit: PAGE_SIZE }, params)).then(function (items) {
			items.forEach(function (item) { grid.appendChild(UI.posterCard(item)); });
			if (!loaded) Nav.focusFirst(el);
			loaded += items.length;
			done = items.length < PAGE_SIZE;
			loading = false;
		}).catch(function (error) {
			loading = false;
			UI.toast('Unable to load ' + parent.Name);
			console.warn(error);
		});
	}

	el.addEventListener('navfocus', function (e) {
		var index = Array.prototype.indexOf.call(grid.children, e.target);
		if (index >= 0 && grid.children.length - index < 21) more();
	});

	return {
		el: el,
		onShow: function (first) { if (first) more(); }
	};
}
