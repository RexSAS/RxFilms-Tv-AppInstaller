// Server address → "who is watching?" at every launch → password only the first time for each profile.
function LoginScreen() {
	var el = h('div', 'screen login');
	var box = h('div', 'login-box');
	var logo = h('img', 'login-logo');
	logo.src = 'img/logo.png';
	el.appendChild(box);

	function render(nodes) {
		box.innerHTML = '';
		box.appendChild(logo);
		nodes.forEach(function (node) { box.appendChild(node); });
		Nav.focusFirst(box);
	}

	function field(placeholder, type, value) {
		var input = h('input', 'field');
		input.type = type;
		input.placeholder = placeholder;
		input.value = value || '';
		return focusable(input, function () {
			input.focus();
			window.scrollTo(0, 0);
		});
	}

	function button(label, action) { return focusable(h('div', 'button', label), action); }

	function normalize(address) {
		address = address.trim().replace(/\/+$/, '');
		if (!address) return '';
		return /^https?:\/\//i.test(address) ? address : 'http://' + address;
	}

	function serverStep() {
		var address = field('Server address, e.g. 192.168.1.10:8096', 'url', Api.server);
		var connect = button('Connect', function () {
			var url = normalize(address.value);
			if (!url) return UI.toast('Enter your server address');
			UI.toast('Connecting…');
			Api.request('GET', '/System/Info/Public', null, null, url).then(function () {
				Api.setServer(url);
				usersStep();
			}).catch(function () { UI.toast('No Jellyfin server found at ' + url); });
		});
		address.setAttribute('data-autofocus', '');
		address._submit = connect._select;
		render([h('h1', null, 'Connect to your server'), address, connect]);
	}

	function usersStep() {
		Api.get('/Users/Public').then(function (users) {
			// Profiles hidden from the login screen can only sign in by name.
			if (!users.length) return passwordStep('', false);
			var list = h('div', 'users');
			users.forEach(function (user) {
				var card = focusable(h('div', 'user'), function () { pickUser(user); });
				var avatar = h('div', 'avatar', user.PrimaryImageTag ? '' : user.Name.charAt(0).toUpperCase());
				if (user.PrimaryImageTag) avatar.style.backgroundImage = 'url("' + Api.server + '/Users/' + user.Id + '/Images/Primary?tag=' + user.PrimaryImageTag + '")';
				card.appendChild(avatar);
				card.appendChild(h('div', 'name', user.Name));
				list.appendChild(card);
			});
			render([h('h1', null, 'Who is watching?'), list, button('Change server', serverStep)]);
		}).catch(serverStep);
	}

	function pickUser(user) {
		var token = Api.savedTokens()[user.Id];
		if (!token) return user.HasPassword ? passwordStep(user.Name, true) : signIn(user.Name, '');
		Api.setSession(token, user.Id);
		// A revoked token (signed out from the server's dashboard) falls back to the password.
		Api.get('/Users/Me').then(function () {
			App.reset(HomeScreen());
		}).catch(function () {
			Api.setSession('', '');
			passwordStep(user.Name, true);
		});
	}

	function passwordStep(name, canGoBack) {
		var username = field('Username', 'text', name);
		var password = field('Password', 'password');
		var submit = button('Sign in', function () { signIn(username.value.trim(), password.value); });
		(name ? password : username).setAttribute('data-autofocus', '');
		username._submit = function () { Nav.focus(password); };
		password._submit = submit._select;
		var nodes = [h('h1', null, 'Sign in'), username, password, submit];
		nodes.push(canGoBack ? button('Back', usersStep) : button('Change server', serverStep));
		render(nodes);
	}

	function signIn(name, password) {
		Api.post('/Users/AuthenticateByName', { Username: name, Pw: password }).then(function (result) {
			Api.setSession(result.AccessToken, result.User.Id);
			App.reset(HomeScreen());
		}).catch(function () { UI.toast('Wrong username or password'); });
	}

	return {
		el: el,
		isLogin: true,
		onShow: function (first) { if (first) (Api.server ? usersStep : serverStep)(); }
	};
}
