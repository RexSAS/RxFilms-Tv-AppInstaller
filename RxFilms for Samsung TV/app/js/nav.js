// D-pad focus. Focusable elements have the "focusable" class; el._select runs on OK and el._nav[dir] overrides a direction
// (return false from it to fall back to the nearest element).
// Bars marked "nav-explicit" (home toolbar, page back/home buttons) overlap content, so they are only reached when
// nothing else lies in that direction, or through an explicit el._nav.
var Keys = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 461, ESC: 27, BACKSPACE: 8, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412 };
var DIRS = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' };

function offsetWithin(el, ancestor) {
	var top = 0;
	while (el && el !== ancestor) {
		top += el.offsetTop;
		el = el.offsetParent;
	}
	return top;
}

function visible(el) { return el.offsetParent !== null; }

/** Slides the enclosing .track-x / .track-y so [el] is on screen; offsets come from layout, not from the animated position. */
function reveal(el) {
	var x = el.closest('.track-x');
	if (x) {
		var width = x.parentNode.clientWidth;
		var pad = 80;
		var off = +x.getAttribute('data-off') || 0;
		var left = el.offsetLeft;
		var right = left + el.offsetWidth;
		if (left - off < pad) off = Math.max(0, left - pad);
		else if (right - off > width - pad) off = right - width + pad;
		x.setAttribute('data-off', off);
		x.style.transform = 'translate3d(' + -off + 'px, 0, 0)';
	}
	var y = el.closest('.track-y');
	if (y) {
		var target = el.closest('.row') || el;
		var anchor = +y.getAttribute('data-anchor') || 0;
		var max = Math.max(0, y.offsetHeight - y.parentNode.clientHeight + 60);
		var offY = Math.max(0, Math.min(offsetWithin(target, y) - anchor, max));
		y.style.transform = 'translate3d(0, ' + -offY + 'px, 0)';
	}
}

var Nav = {
	current: null,
	scope: document.body,

	focus: function (el, fromPointer) {
		if (!el) return;
		var previous = this.current;
		if (previous && previous !== el) {
			previous.classList.remove('focused');
			if (previous === document.activeElement) previous.blur();
		}
		this.current = el;
		el.classList.add('focused');
		if (!fromPointer) reveal(el);
		if (el._focus) el._focus();
		el.dispatchEvent(new CustomEvent('navfocus', { bubbles: true }));
	},

	focusFirst: function (root) {
		root = root || this.scope;
		var el = root.querySelector('.focusable[data-autofocus]');
		if (!el || !visible(el)) el = Array.prototype.filter.call(root.querySelectorAll('.focusable'), visible)[0];
		this.focus(el);
	},

	move: function (dir) {
		var cur = this.current;
		if (!cur || !this.scope.contains(cur) || !visible(cur)) return this.focusFirst();
		if (cur._nav && cur._nav[dir] && cur._nav[dir]() !== false) return;
		var bar = cur.closest('.nav-explicit');
		// Along a row only its own cards count; that keeps holding left/right cheap on rows of hundreds of cards.
		var track = (dir === 'left' || dir === 'right') && cur.closest('.track-x');
		var all = Array.prototype.filter.call((track || this.scope).querySelectorAll('.focusable'), function (el) {
			return el !== cur && visible(el);
		});
		var candidates = all.filter(function (el) {
			var elBar = el.closest('.nav-explicit');
			return !elBar || elBar === bar;
		});
		if (this.focusNearest(cur, candidates, dir)) return;
		if (dir === 'up') this.focusNearest(cur, all, dir);
	},

	focusNearest: function (cur, candidates, dir) {
		var index = Logic.pickNearest(cur.getBoundingClientRect(), candidates.map(function (el) { return el.getBoundingClientRect(); }), dir);
		if (index < 0) return false;
		this.focus(candidates[index]);
		return true;
	},

	select: function () {
		var cur = this.current;
		if (cur && this.scope.contains(cur) && cur._select) cur._select();
	}
};
