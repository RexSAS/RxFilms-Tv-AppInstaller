package org.jellyfin.androidtv.util.sdk

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/** The SDK's own client for HTTP, with [ReliableSocketApi] as its WebSocket. */
class ReliableApiClient(
	private val delegate: ApiClient,
	socketConnectionFactory: SocketConnectionFactory,
) : ApiClient() {
	override val baseUrl: String? get() = delegate.baseUrl
	override val accessToken: String? get() = delegate.accessToken
	override val clientInfo: ClientInfo get() = delegate.clientInfo
	override val deviceInfo: DeviceInfo get() = delegate.deviceInfo
	override val httpClientOptions: HttpClientOptions get() = delegate.httpClientOptions

	private val socket = lazy { ReliableSocketApi(this, socketConnectionFactory) }
	override val webSocket: ReliableSocketApi by socket

	override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) {
		delegate.update(baseUrl, accessToken, clientInfo, deviceInfo)
		if (socket.isInitialized()) socket.value.notifyApiClientUpdate()
	}

	override suspend fun request(
		method: HttpMethod,
		pathTemplate: String,
		pathParameters: Map<String, Any?>,
		queryParameters: Map<String, Any?>,
		requestBody: Any?,
	): RawResponse = delegate.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
}

class ReliableApiClientFactory(private val delegate: ApiClientFactory) : ApiClientFactory {
	override fun create(
		baseUrl: String?,
		accessToken: String?,
		clientInfo: ClientInfo,
		deviceInfo: DeviceInfo,
		httpClientOptions: HttpClientOptions,
		socketConnectionFactory: SocketConnectionFactory,
	): ApiClient = ReliableApiClient(
		delegate.create(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions, socketConnectionFactory),
		socketConnectionFactory,
	)
}
