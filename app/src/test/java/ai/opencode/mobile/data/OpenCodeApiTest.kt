package ai.opencode.mobile.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsBasicAuthAndEncodedDirectory() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val api = OpenCodeApi(
            ConnectionProfile(
                baseUrl = server.url("/").toString(),
                directory = "C:/work/project with space",
                username = "mobile",
                password = "secret",
            ),
        )

        api.sessions()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/experimental/session", request.requestUrl?.encodedPath)
        assertEquals("C:/work/project with space", request.requestUrl?.queryParameter("directory"))
        assertEquals("true", request.requestUrl?.queryParameter("roots"))
        assertEquals("250", request.requestUrl?.queryParameter("limit"))
        assertTrue(request.getHeader("Authorization").orEmpty().startsWith("Basic "))
    }

    @Test
    fun sendsAsyncPromptWithAgentAndModel() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val api = OpenCodeApi(ConnectionProfile(baseUrl = server.url("/").toString()))

        api.promptAsync(
            sessionId = "ses_1",
            text = "Implement the change",
            agent = "build",
            model = ModelChoice("openai", "gpt-5", "GPT-5"),
        )

        val request = server.takeRequest()
        val body = OpenCodeJson.objectValue(request.body.readUtf8())
        assertEquals("POST", request.method)
        assertEquals("/session/ses_1/prompt_async", request.requestUrl?.encodedPath)
        assertEquals("build", body.string("agent"))
        assertEquals("openai", body.objectOrNull("model")?.string("providerID"))
        assertEquals(
            "Implement the change",
            body.arrayOrNull("parts")?.firstOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }?.string("text"),
        )
    }

    @Test
    fun createsSessionInExplicitProjectDirectory() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"ses_new","slug":"new","projectID":"project","directory":"D:/work/target","title":"New work","version":"1","time":{"created":1,"updated":1}}""",
            ),
        )
        val api = OpenCodeApi(ConnectionProfile(baseUrl = server.url("/").toString()))

        val session = api.createSession("New work", "D:/work/target")

        val request = server.takeRequest()
        assertEquals("/session", request.requestUrl?.encodedPath)
        assertEquals("D:/work/target", request.requestUrl?.queryParameter("directory"))
        assertEquals("ses_new", session.id)
    }

    @Test
    fun readsGlobalSseEnvelope() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"directory\":\"D:/work/target\",\"payload\":{\"id\":\"evt_1\",\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_1\"}}}\n\n",
                ),
        )
        val api = OpenCodeApi(ConnectionProfile(baseUrl = server.url("/").toString()))

        val event = api.events().first()

        val request = server.takeRequest()
        assertEquals("/global/event", request.requestUrl?.encodedPath)
        assertEquals("session.idle", event.type)
        assertEquals("D:/work/target", event.directory)
    }

    @Test
    fun exposesAuthenticationFailureFromEventStream() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val api = OpenCodeApi(ConnectionProfile(baseUrl = server.url("/").toString()))

        val error = runCatching { api.events().first() }.exceptionOrNull()

        assertTrue(error is OpenCodeApiException)
        assertEquals(401, (error as OpenCodeApiException).statusCode)
    }

    @Test
    fun normalizesBareHostAndPreservesHttps() {
        assertEquals("http://192.168.1.5:4096", OpenCodeApi.normalizeBaseUrl("192.168.1.5:4096/"))
        assertEquals("https://oc.example.com", OpenCodeApi.normalizeBaseUrl("https://oc.example.com/"))
    }

    @Test
    fun fallsBackToConfigProvidersOnlyWhenProviderEndpointIsMissing() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"connected":[],"all":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        val api = OpenCodeApi(ConnectionProfile(baseUrl = server.url("/").toString()))

        val catalog = api.models()

        assertTrue(catalog.models.isEmpty())
        assertEquals("/provider", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/config/providers", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/config", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun doesNotMaskProviderAuthenticationFailureWithFallback() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val api = OpenCodeApi(ConnectionProfile(baseUrl = server.url("/").toString()))

        val error = runCatching { api.models() }.exceptionOrNull()

        assertTrue(error is OpenCodeApiException)
        assertEquals(401, (error as OpenCodeApiException).statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun bridgeRequestUsesBearerWithoutServerBasicCredentials() = runTest {
        val bridge = MockWebServer()
        bridge.start()
        try {
            bridge.enqueue(
                MockResponse().setBody(
                    """{"desktop":{},"network":{},"models":{}}""",
                ),
            )
            val api = OpenCodeApi(
                ConnectionProfile(
                    baseUrl = server.url("/").toString(),
                    username = "mobile",
                    password = "server-secret",
                    bridgeBaseUrl = bridge.url("/").toString(),
                    bridgeToken = "bridge-secret",
                ),
            )

            api.desktopBridge()

            val request = bridge.takeRequest()
            assertEquals("/state", request.requestUrl?.encodedPath)
            assertEquals("Bearer bridge-secret", request.getHeader("Authorization"))
        } finally {
            bridge.shutdown()
        }
    }
}
