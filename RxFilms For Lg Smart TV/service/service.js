// Makes Seerr requests for the app: Seerr sends no CORS headers, so the TV's browser engine blocks them from the page.
// ES5 only: older webOS TVs run an old Node.
var Service = require('webos-service');
var http = require('http');
var https = require('https');
var url = require('url');

var service = new Service('org.rxfilms.tv.service');

/** Payload {url, method, headers, body}; responds {status, body}. */
service.register('request', function (message) {
	var payload = message.payload || {};
	var target = url.parse(payload.url || '');
	if (target.protocol !== 'http:' && target.protocol !== 'https:') {
		return message.respond({ returnValue: false, errorText: 'Unsupported URL: ' + payload.url });
	}
	var request = (target.protocol === 'https:' ? https : http).request({
		hostname: target.hostname,
		port: target.port,
		path: target.path,
		method: payload.method || 'GET',
		headers: payload.headers || {}
	}, function (response) {
		var body = '';
		response.setEncoding('utf8');
		response.on('data', function (chunk) { body += chunk; });
		response.on('end', function () { message.respond({ returnValue: true, status: response.statusCode, body: body }); });
	});
	request.setTimeout(10000, function () { request.abort(); });
	request.on('error', function (error) { message.respond({ returnValue: false, errorText: error.message }); });
	if (payload.body) request.write(payload.body);
	request.end();
});
