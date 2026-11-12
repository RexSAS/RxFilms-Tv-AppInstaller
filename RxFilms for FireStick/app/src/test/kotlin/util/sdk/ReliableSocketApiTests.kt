package org.jellyfin.androidtv.util.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.LibraryChangedMessage

@OptIn(ExperimentalCoroutinesApi::class)
class ReliableSocketApiTests : FunSpec({
	test("resubscribing right after the last subscriber left keeps the socket alive") {
		runTest {
			val fixture = Fixture(this)
			val first = fixture.socket.subscribe<LibraryChangedMessage>().launchIn(backgroundScope)
			runCurrent()
			fixture.connection.connects shouldBe 1
			first.cancelAndJoin()
			val received = mutableListOf<LibraryChangedMessage>()
			fixture.socket.subscribe<LibraryChangedMessage>().onEach { received += it }.launchIn(backgroundScope)
			runCurrent()
			advanceTimeBy(60_000)
			runCurrent()
			// SDK 1.8.12 closed the socket here (its count hit 0) and never reopened it.
			fixture.connection.disconnects shouldBe 0
			fixture.connection.connected shouldBe true
			fixture.connection.push(LIBRARY_CHANGED)
			runCurrent()
			received.size shouldBe 1
		}
	}

	test("a clean close by the server is reconnected while someone listens") {
		runTest {
			val fixture = Fixture(this)
			fixture.socket.subscribe<LibraryChangedMessage>().launchIn(backgroundScope)
			runCurrent()
			fixture.connection.serverClose()
			runCurrent()
			advanceTimeBy(1_500)
			runCurrent()
			fixture.connection.connects shouldBe 2
			fixture.connection.connected shouldBe true
		}
	}

	test("failed reconnects back off and keep trying") {
		runTest {
			val fixture = Fixture(this)
			fixture.socket.subscribe<LibraryChangedMessage>().launchIn(backgroundScope)
			runCurrent()
			fixture.connection.reachable = false
			fixture.connection.serverClose()
			runCurrent()
			advanceTimeBy(120_000)
			runCurrent()
			val attempts = fixture.connection.connects
			(attempts in 4..10) shouldBe true
			fixture.connection.reachable = true
			advanceTimeBy(31_000)
			runCurrent()
			fixture.connection.connected shouldBe true
		}
	}

	test("the socket closes once after the last subscriber left and stays closed") {
		runTest {
			val fixture = Fixture(this)
			val job = fixture.socket.subscribe<LibraryChangedMessage>().launchIn(backgroundScope)
			runCurrent()
			job.cancelAndJoin()
			advanceTimeBy(6_000)
			runCurrent()
			fixture.connection.disconnects shouldBe 1
			advanceTimeBy(120_000)
			runCurrent()
			fixture.connection.connects shouldBe 1
		}
	}

	test("the subscription count stays exact under concurrent subscribe and cancel") {
		val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
		val fixture = Fixture(scope)
		runBlocking {
			val jobs = List(500) {
				scope.launch {
					val job = fixture.socket.subscribe<LibraryChangedMessage>().launchIn(this)
					yield()
					job.cancel()
				}
			}
			jobs.joinAll()
			withTimeout(5_000) { while (fixture.socket.activeSubscriptions != 0) yield() }
			fixture.socket.activeSubscriptions shouldBe 0
		}
		scope.cancel()
	}
})

private const val LIBRARY_CHANGED = """{"MessageType":"LibraryChanged","MessageId":"00000000-0000-0000-0000-000000000001","Data":{"FoldersAddedTo":[],"FoldersRemovedFrom":[],"ItemsAdded":[],"ItemsRemoved":[],"ItemsUpdated":[],"CollectionFolders":[],"IsEmpty":true}}"""
private const val FORCE_KEEP_ALIVE = """{"MessageType":"ForceKeepAlive","MessageId":"00000000-0000-0000-0000-000000000002","Data":60}"""

private class Fixture(scope: CoroutineScope) {
	constructor(test: TestScope) : this(test.backgroundScope)

	val connection = FakeConnection()
	val socket = ReliableSocketApi(FakeApiClient(), { _, _ -> connection }, scope)
}

private class FakeConnection : SocketConnection {
	private val mutableState = BufferedSocketState(SocketConnectionState.Disconnected())
	override val state = mutableState
	@Volatile var connects = 0
	@Volatile var disconnects = 0
	@Volatile var connected = false
	@Volatile var reachable = true

	override suspend fun connect(
		url: String,
		clientName: String,
		clientVersion: String,
		deviceId: String,
		deviceName: String,
		accessToken: String,
	): Boolean {
		connects++
		mutableState.update(SocketConnectionState.Connecting)
		if (!reachable) {
			mutableState.update(SocketConnectionState.Disconnected(IllegalStateException("unreachable")))
			return false
		}
		connected = true
		mutableState.update(SocketConnectionState.Message(FORCE_KEEP_ALIVE))
		return true
	}

	override suspend fun send(message: String) = connected

	override suspend fun disconnect() {
		if (!connected) return
		disconnects++
		connected = false
		mutableState.update(SocketConnectionState.Disconnected())
	}

	fun serverClose() {
		connected = false
		mutableState.update(SocketConnectionState.Disconnected())
	}

	fun push(message: String) = mutableState.update(SocketConnectionState.Message(message))
}

private class FakeApiClient : ApiClient() {
	override val baseUrl = "http://server"
	override val accessToken = "token"
	override val clientInfo = ClientInfo("RxFilms", "1.3.0")
	override val deviceInfo = DeviceInfo("device", "TV")
	override val httpClientOptions = HttpClientOptions()
	override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) = Unit
	override suspend fun request(
		method: HttpMethod,
		pathTemplate: String,
		pathParameters: Map<String, Any?>,
		queryParameters: Map<String, Any?>,
		requestBody: Any?,
	): RawResponse = error("not used")
	override val webSocket: SocketApi get() = error("not used")
}
