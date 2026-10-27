// Run with: node --test test/*.test.js
const test = require('node:test');
const assert = require('node:assert');
const Logic = require('../app/js/logic.js');

test('seerr titles keep movies and series, map status and Jellyfin id', () => {
	const titles = Logic.seerrTitles({ results: [
		{ id: 1, mediaType: 'movie', title: 'Dune', releaseDate: '2021-09-15', posterPath: '/p.jpg', mediaInfo: { status: 5, jellyfinMediaId: 'abc' } },
		{ id: 2, mediaType: 'tv', name: 'Dark', firstAirDate: '2017-12-01' },
		{ id: 3, mediaType: 'person', name: 'Someone' },
	] });
	assert.deepStrictEqual(titles.map((t) => t.title), ['Dune', 'Dark']);
	assert.strictEqual(titles[0].year, '2021');
	assert.strictEqual(titles[0].posterUrl, 'https://image.tmdb.org/t/p/w342/p.jpg');
	assert.strictEqual(Logic.seerrAvailableId(titles[0]), 'abc');
	assert.strictEqual(titles[1].status, null);
	assert.strictEqual(Logic.seerrAvailableId(titles[1]), null);
});

test('top ten picks one kind across pages, drops duplicates, ranks from 1', () => {
	const m = (id) => ({ id, mediaType: 'movie' });
	const s = (id) => ({ id, mediaType: 'tv' });
	const top = Logic.topTen([[m(1), s(2), m(3)], [m(3), m(4), s(5)]], true, 2);
	assert.deepStrictEqual(top.map((t) => [t.id, t.rank]), [[1, 1], [3, 2]]);
	assert.deepStrictEqual(Logic.topTen([[m(1), s(2)]], false, 10).map((t) => t.id), [2]);
});

test('spatial navigation stays on the row and moves to the nearest row below', () => {
	const r = (left, top, w = 100, hgt = 150) => ({ left, top, right: left + w, bottom: top + hgt });
	const from = r(100, 100);
	const rects = [r(220, 100), r(340, 100), r(90, 300), r(400, 300), r(2000, 300)];
	assert.strictEqual(Logic.pickNearest(from, rects, 'right'), 0);
	assert.strictEqual(Logic.pickNearest(from, rects, 'down'), 2);
	assert.strictEqual(Logic.pickNearest(from, rects, 'left'), -1);
	// End of a row: the next row's cards are not "to the right".
	assert.strictEqual(Logic.pickNearest(r(340, 100), [r(2000, 300)], 'right'), -1);
});

test('down from the full-width banner lands on the next row, not a centered card further down', () => {
	const r = (left, top, w, hgt) => ({ left, top, right: left + w, bottom: top + hgt });
	const banner = r(0, 0, 1920, 950);
	const nextUp = [r(80, 1017, 400, 285), r(504, 1017, 400, 285)];
	const top10 = [r(80, 1403, 220, 368), r(812, 1403, 220, 368)];
	assert.strictEqual(Logic.pickNearest(banner, nextUp.concat(top10), 'down'), 1);
	// And back up from a Top 10 card reaches Next up, the row right above.
	assert.ok(Logic.pickNearest(top10[1], nextUp.concat([banner]), 'up') < 2);
});

test('direct play unless another audio track is wanted', () => {
	const source = {
		Id: 'ms', SupportsDirectPlay: true,
		MediaStreams: [{ Type: 'Video', Index: 0 }, { Type: 'Audio', Index: 1, IsDefault: true }, { Type: 'Audio', Index: 2 }],
	};
	assert.strictEqual(Logic.streamFor('http://s', 't', 'i', source, 'p', 1).method, 'DirectPlay');
	assert.strictEqual(Logic.streamFor('http://s', 't', 'i', source, 'p', 2), null);
	const transcoded = Object.assign({}, source, { SupportsDirectPlay: false, TranscodingUrl: '/videos/i/master.m3u8?x=1' });
	assert.deepStrictEqual(Logic.streamFor('http://s', 't', 'i', transcoded, 'p', 2), { method: 'Transcode', url: 'http://s/videos/i/master.m3u8?x=1' });
});

test('time format', () => {
	assert.strictEqual(Logic.formatTime(65), '1:05');
	assert.strictEqual(Logic.formatTime(3725), '1:02:05');
});
