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
import kotlinx.serialization.json.jsonArray
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

    @Test
    fun `GET events returns 200 with empty items when no events exist`() = testApp {
        val response = client.get("/events")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0L,  body["totalCount"]!!.jsonPrimitive.content.toLong())
        assertEquals(1,   body["page"]!!.jsonPrimitive.content.toInt())
        assertEquals(20,  body["pageSize"]!!.jsonPrimitive.content.toInt())
        assertEquals(0,   body["items"]!!.jsonArray.size)
    }

    @Test
    fun `GET events returns 200 with all events and correct fields`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val repo = FakeEventRepository().also {
            it.save(Event(id = id1, sourceName = "github", eventType = "push",           idempotencyKey = "k1", payloadJson = "{}", correlationId = "c1", receivedAt = Clock.System.now()))
            it.save(Event(id = id2, sourceName = "stripe", eventType = "charge.created", idempotencyKey = "k2", payloadJson = "{}", correlationId = "c2", receivedAt = Clock.System.now()))
        }
        testApp(repo) {
            val response = client.get("/events")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET events filters by sourceName`() {
        val repo = FakeEventRepository().also {
            it.save(Event(id = UUID.randomUUID(), sourceName = "github", eventType = "push",           idempotencyKey = "k1", payloadJson = "{}", correlationId = "c1", receivedAt = Clock.System.now()))
            it.save(Event(id = UUID.randomUUID(), sourceName = "stripe", eventType = "charge.created", idempotencyKey = "k2", payloadJson = "{}", correlationId = "c2", receivedAt = Clock.System.now()))
        }
        testApp(repo) {
            val response = client.get("/events?sourceName=github")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            val items = body["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals("github", items[0].jsonObject["sourceName"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET events filters by type`() {
        val repo = FakeEventRepository().also {
            it.save(Event(id = UUID.randomUUID(), sourceName = "github", eventType = "push",   idempotencyKey = "k1", payloadJson = "{}", correlationId = "c1", receivedAt = Clock.System.now()))
            it.save(Event(id = UUID.randomUUID(), sourceName = "github", eventType = "delete", idempotencyKey = "k2", payloadJson = "{}", correlationId = "c2", receivedAt = Clock.System.now()))
        }
        testApp(repo) {
            val response = client.get("/events?type=push")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals("push", body["items"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `GET events respects page and pageSize`() {
        val repo = FakeEventRepository().also { r ->
            repeat(5) { i ->
                r.save(Event(id = UUID.randomUUID(), sourceName = "github", eventType = "push", idempotencyKey = "k$i", payloadJson = "{}", correlationId = "c$i", receivedAt = Clock.System.now()))
            }
        }
        testApp(repo) {
            val response = client.get("/events?page=2&pageSize=2")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(5L, body["totalCount"]!!.jsonPrimitive.content.toLong())
            assertEquals(2,  body["page"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["pageSize"]!!.jsonPrimitive.content.toInt())
            assertEquals(2,  body["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun `GET events returns 400 for invalid status`() = testApp {
        val response = client.get("/events?status=INVALID_STATUS")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET events returns 400 for invalid dateFrom`() = testApp {
        val response = client.get("/events?dateFrom=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET events returns 400 for invalid dateTo`() = testApp {
        val response = client.get("/events?dateTo=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
