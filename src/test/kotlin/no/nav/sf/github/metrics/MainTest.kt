package no.nav.sf.github.metrics

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

import kotlinx.coroutines.runBlocking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * tests for the entire app, sans routing and validation
 */
class MainTest {
    //@Test
    //fun happyPath() = runBlocking {
    //    val response = updateAndForward(
    //        """
    //        # TYPE answer gauge
    //        answer{scope="life"} 42
    //        """.trimMargin(),
    //        "xyzzy",
    //        null,
    //        FakePersistence()
    //    )
    //    assertEquals(200, response.status.value)
    //}
}
