package no.nav.sf.github.metrics

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest {
    //@Test
    //fun `server is up and talks to local prometheus gate server, will fail if prometheus gateway isn't running at ip 0xac110003`() = testApplication {
    //    application {
    //        module()
    //    }
    //    val response = client.post("/measures/job/foo") {
    //        contentType(ContentType.Text.Plain)
    //        setBody("omg wtf")
    //    }
    //    assertEquals(HttpStatusCode.OK, response.status)
    //    assertEquals("success", response.bodyAsText())
    //}
}
