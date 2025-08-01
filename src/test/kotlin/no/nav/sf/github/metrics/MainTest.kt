package no.nav.sf.github.metrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*

import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType

import io.ktor.server.testing.*

import kotlinx.coroutines.runBlocking

import kotlinx.serialization.json.Json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * tests for the entire app. doesn't actually test the _main_ method, but uses
 * an application with the same module as _main_ calls, but using fake runners,
 * persistance, and forwarder.
 */
class MainTest {

    /**
     * creates a key pair, signs the given message, and returns:
     * - a fake runners instance with the given runner name and the public key
     * - the signed message
     */
    private fun getRunnersAndSignature(
        runner: String,
        message: String
    ): Pair<IRunners, String> {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val privateKey = keyPair.private as ECPrivateKey
        val publicKey = keyPair.public as ECPublicKey
        val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded)
        val runners = FakeRunners().apply {
            set(runner, publicKeyBase64)
        }
        val signature = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(message.toByteArray())
            }.sign()
        )
        return Pair(runners, signature)
    }

    /**
     * end to end test, to the extent possible. tests:
     * - signature validation
     * - message parsing
     * - deserialization of types and stat entries
     * - save to db (faked)
     * - gateway response (faked)
     */
    @Test
    fun endToEnd() = testApplication {
        val persistence = FakePersistence()
        val forwarder = FakeForwarder()
        val runner = "bogus"
        val message = """
            # TYPE answer gauge
            answer{base="10"} 54
            answer{base="13"} 42
            # TYPE question counter
            question{} 0
            """
        val (runners, signature) = getRunnersAndSignature(runner, message)
        application {
            module(runners, persistence, forwarder)
            val client = HttpClient(CIO)
        }
        val response = client.post("http://localhost/measures/job/xyzzy") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(Payload(message, runner, signature)))
        }
        assertEquals(200, response.status.value)
        assertEquals(42.0, persistence.getEntry(
            "answer",
            "base=\"13\",instance=\"default\""
        ))
        assertEquals("counter", persistence.types["question"])
    }
}
