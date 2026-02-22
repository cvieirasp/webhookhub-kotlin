package io.github.cvieirasp.api.ingest

import io.github.cvieirasp.api.plugins.configureSerialization
import io.github.cvieirasp.api.plugins.configureStatusPages
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [eventRoutes].
 */
class EventUnitRoutesTest {

    private fun testApp(
        repo: FakeEventRepository = FakeEventRepository(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing { eventRoutes(EventUseCase(repo)) }
        }
        block()
    }

    @Test
    fun `GET events by id returns 200 with correct fields`() {
        val id = UUID.randomUUID()
        val repo = FakeEventRepository().also {
            it.save(
                Event(
                    id             = id,
                    sourceName     = "github",
                    eventType      = "push",
                    idempotencyKey = "abc123",
                    payloadJson    = """{"ref":"main"}""",
                    correlationId  = "corr-1",
                    receivedAt     = Clock.System.now(),
                )
            )
        }

        testApp(repo) {
            val response = client.get("/events/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(id.toString(),    body["id"]!!.jsonPrimitive.content)
            assertEquals("github",         body["sourceName"]!!.jsonPrimitive.content)
            assertEquals("push",           body["type"]!!.jsonPrimitive.content)
            assertEquals("abc123",         body["idempotencyKey"]!!.jsonPrimitive.content)
            assertEquals("corr-1",         body["correlationId"]!!.jsonPrimitive.content)
            assertEquals("""{"ref":"main"}""", body["payload"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET events by id returns 404 when event does not exist`() = testApp {
        val response = client.get("/events/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
