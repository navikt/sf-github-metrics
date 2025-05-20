package no.nav.sf.github.metrics

import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.*
import javax.crypto.spec.SecretKeySpec

/**
 * ensures that a given message's signature is valid.
 */
class MessageValidator {

    companion object {
        val keyFactory = KeyFactory.getInstance("EC")
    }

    private fun keyFromBase64(publicKey: String): PublicKey =
        keyFactory.generatePublic(
            X509EncodedKeySpec(
                Base64.getDecoder().decode(publicKey)
            )
        )

    /**
     * ensures message matches signature from a whitelisted key.
     * TODO: consider also validating the job on which metrics are stored.
     */
    fun isValid(message: String, publicKey: String, signature: String): Boolean {
        try {
            val key = keyFromBase64(publicKey)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(message.toByteArray())
            return sig.verify(Base64.getDecoder().decode(signature))
        } catch(iae: IllegalArgumentException) {
            logger.error("probably received some invalid base64: ${iae.stackTraceToString()}")
            return false
        }
    }
}
