// Screen stack, remote keys and the magic remote pointer. Screens are {el, onShow(first), onHide, onKey(e), onBack, destroy}.
var App = {
	root: document.getElementById('screens'),
	stack: [],
	modal: null,
	modalReturn: null,
	userName: '',

	top: function () { return this.stack[this.stack.length - 1]; },

	push: function (screen) {
		var top = this.top();
		if (top) {
			top.lastFocus = Nav.current;
			top.el.style.display = 'none';
			if (top.onHide) top.onHide();
		}
		this.stack.push(screen);
		this.root.appendChild(screen.el);
		Nav.scope = screen.el;
		if (screen.onShow) screen.onShow(true);
		if (!screen.el.contains(Nav.current)) Nav.focusFirst(screen.el);
	},

	pop: function () {
		var top = this.stack.pop();
		if (top.destroy) top.destroy();
		this.root.removeChild(top.el);
	},

	back: function () {
		if (this.modal) return this.closeModal();
		var top = this.top();
		if (top.onBack && top.onBack()) return;
		if (this.stack.length <= 1) return window.close();
		this.pop();
		this.reshow();
	},

	/** Back to the home screen from any depth. */
	home: function () {
		while (this.stack.length > 1) this.pop();
		this.reshow();
	},

	reshow: function () {
		var screen = this.top();
		screen.el.style.display = '';
		Nav.scope = screen.el;
		if (screen.lastFocus && screen.el.contains(screen.lastFocus)) Nav.focus(screen.lastFocus);
		else Nav.focusFirst(screen.el);
		if (screen.onShow) screen.onShow(false);
	},

	replace: function (screen) {
		this.pop();
		this.push(screen);
	},

	reset: function (screen) {
		if (this.modal) this.closeModal();
		while (this.stack.length) this.pop();
		Backdrop.show(null);
		this.push(screen);
	},

	openModal: function (el) {
		this.modal = el;
		this.modalReturn = Nav.current;
		document.body.appendChild(el);
		Nav.scope = el;
		Nav.focusFirst(el);
	},

	closeModal: function () {
		document.body.removeChild(this.modal);
		this.modal = null;
		Nav.scope = this.top().el;
		Nav.focus(this.modalReturn);
	},

	openItem: function (item) {
		if (item.Type === 'Season') this.push(DetailsScreen(item.SeriesId));
		else if (item.IsFolder && item.Type !== 'Series') this.push(LibraryScreen(item));
		else this.push(DetailsScreen(item.Id));
	},

	/** In a SyncPlay group the server starts playback for everyone, this TV included. */
	play: function (item, startSeconds) {
		if (SyncPlay.group) SyncPlay.setQueue(item, startSeconds);
		else this.push(PlayerScreen(item, startSeconds));
	},

	/** Back to "who is watching?"; the profile stays remembered. */
	switchUser: function () {
		SyncPlay.disconnect();
		this.reset(LoginScreen());
	},

	/** Forgets this profile on the TV: the next time it needs the password. */
	signOut: function () {
		Api.post('/Sessions/Logout').catch(function () {}).then(function () {
			Api.setSession('', '');
			App.switchUser();
		});
	},

	/** Token revoked or expired. */
	signedOut: function () {
		if (!Api.token) return;
		Api.setSession('', '');
		if (!this.top().isLogin) this.switchUser();
	}
};

var lastEnterKey = 0;
var lastMoveKey = 0;

document.addEventListener('keydown', function (e) {
	var key = e.keyCode;
	if (key === Keys.ENTER) lastEnterKey = Date.now();
	var active = document.activeElement;
	if (active && active.tagName === 'INPUT') {
		if (key === Keys.ENTER) {
			active.blur();
			if (active._submit) active._submit();
			e.preventDefault();
			return;
		}
		// Typing, caret moves and deleting stay in the field; up/down leave it and back closes the keyboard.
		if (key !== Keys.UP && key !== Keys.DOWN && key !== Keys.BACK && key !== Keys.ESC) return;
		active.blur();
		if (key === Keys.BACK || key === Keys.ESC) {
			e.preventDefault();
			return;
		}
	}

	var screen = App.top();
	if (!App.modal && screen && screen.onKey && screen.onKey(e)) {
		e.preventDefault();
		return;
	}
	if (DIRS[key]) {
		e.preventDefault();
		// A held arrow repeats faster than the TV can animate; skipping repeats keeps scrolling smooth.
		if (e.repeat && Date.now() - lastMoveKey < 110) return;
		lastMoveKey = Date.now();
		Nav.move(DIRS[key]);
		return;
	}
	if (key === Keys.ENTER) Nav.select();
	else if (key === Keys.BACK || key === Keys.ESC || key === Keys.BACKSPACE) App.back();
	else return;
	e.preventDefault();
});

// Magic remote pointer: hovering focuses, clicking selects. Pointer focus does not scroll, or rows would slide under the cursor.
document.addEventListener('mouseover', function (e) {
	var el = e.target.closest && e.target.closest('.focusable');
	if (el && el !== Nav.current && Nav.scope.contains(el)) Nav.focus(el, true);
});
document.addEventListener('click', function (e) {
	var el = e.target.closest && e.target.closest('.focusable');
	// The OK button can send both a key and a click.
	if (!el || !Nav.scope.contains(el) || Date.now() - lastEnterKey < 400) return;
	Nav.focus(el, true);
	Nav.select();
});

// Magic remote wheel moves focus like the arrows (sideways with a horizontal wheel), one step per notch.
var lastWheel = 0;
document.addEventListener('wheel', function (e) {
	e.preventDefault();
	if (Date.now() - lastWheel < 150) return;
	lastWheel = Date.now();
	var horizontal = Math.abs(e.deltaX) > Math.abs(e.deltaY);
	var delta = horizontal ? e.deltaX : e.deltaY;
	if (delta) Nav.move(horizontal ? (delta > 0 ? 'right' : 'left') : (delta > 0 ? 'down' : 'up'));
}, { passive: false });

App.reset(LoginScreen());
