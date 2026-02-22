package io.github.cvieirasp.api.plugins

import io.github.cvieirasp.api.DuplicateEventException
import io.github.cvieirasp.api.NotFoundException
import io.github.cvieirasp.api.UnauthorizedException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [configureStatusPages].
 *
 * Verifies that every mapped exception produces the correct HTTP status code
 * and a response body that conforms to the [ErrorResponse] shape.
 */
class StatusPagesTest {

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/bad-request")       { throw IllegalArgumentException("bad input") }
                get("/unauthorized")      { throw UnauthorizedException("not authorised") }
                get("/not-found")         { throw NotFoundException("resource missing") }
                get("/conflict")          { throw DuplicateEventException("already exists") }
                get("/server-error")      { throw RuntimeException("something exploded") }
                get("/with-correlation")  {
                    call.attributes.put(CORRELATION_ID_KEY, "test-corr-id")
                    throw NotFoundException("missing with correlation")
                }
            }
        }
        block()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun assertErrorShape(body: String, expectedStatus: Int, expectedError: String) {
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(expectedStatus, obj["status"]!!.jsonPrimitive.content.toInt(), "status field")
        assertEquals(expectedError,  obj["error"]!!.jsonPrimitive.content,          "error field")
        assertNotNull(obj["message"],   "message field must be present")
        assertNotNull(obj["timestamp"], "timestamp field must be present")
        assertNotNull(obj["correlationId"], "correlationId field must be present (may be null)")
    }

    // ── status code mappings ───────────────────────────────────────────────

    @Test
    fun `IllegalArgumentException returns 400 with correct shape`() = testApp {
        val response = client.get("/bad-request")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertErrorShape(response.bodyAsText(), 400, "Bad Request")
    }

    @Test
    fun `UnauthorizedException returns 401 with correct shape`() = testApp {
        val response = client.get("/unauthorized")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertErrorShape(response.bodyAsText(), 401, "Unauthorized")
    }

    @Test
    fun `NotFoundException returns 404 with correct shape`() = testApp {
        val response = client.get("/not-found")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertErrorShape(response.bodyAsText(), 404, "Not Found")
    }

    @Test
    fun `DuplicateEventException returns 409 with correct shape`() = testApp {
        val response = client.get("/conflict")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertErrorShape(response.bodyAsText(), 409, "Conflict")
    }

    @Test
    fun `unhandled Throwable returns 500 with correct shape`() = testApp {
        val response = client.get("/server-error")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertErrorShape(response.bodyAsText(), 500, "Internal Server Error")
    }

    @Test
    fun `unmatched route returns 404 with correct shape`() = testApp {
        val response = client.get("/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertErrorShape(response.bodyAsText(), 404, "Not Found")
    }

    // ── message content ────────────────────────────────────────────────────

    @Test
    fun `exception message is forwarded in the message field`() = testApp {
        val response = client.get("/bad-request")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("bad input", body["message"]!!.jsonPrimitive.content)
    }

    // ── correlationId propagation ──────────────────────────────────────────

    @Test
    fun `correlationId is null in error body when not set on call`() = testApp {
        val response = client.get("/not-found")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("null", body["correlationId"].toString())
    }

    @Test
    fun `correlationId is included in error body when set on call attributes`() = testApp {
        val response = client.get("/with-correlation")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("test-corr-id", body["correlationId"]!!.jsonPrimitive.content)
    }
}
