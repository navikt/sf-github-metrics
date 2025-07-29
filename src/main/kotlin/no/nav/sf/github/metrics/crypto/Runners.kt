package no.nav.sf.github.metrics

/**
 * for forwarding to occur, runners' public keys must be here.
 */
class Runners: IRunners {
    companion object {
        val publicKeys = mapOf(
            "local" to """
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFkN2
            IQqTBAubNkBZV15vEzaRfC9+DDg5r2fu/xSMnVqm
            jjNX0w5wowRLrYgtmPsf0AveQCqXkzuqXbeDvSWF
            ng==
            """,
            "utah" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExWiWCHrqEcsEvXxEIlnyEt2GkqOIruU0f62D4HqO5T7JXQMvYlNd/aoHln4wpV1+YCpvqWxGS1sYmTEvB1w3aw==",
            "sf-platform" to """
                MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw2sAXjrnJlDvylLNDNjNUXQDdRjf
                +19xyU5uLP35yE7yx7g6ldCvjp768z520lRLENRG6RR138TTQYg02l/luw==
                """
        )
    }

    override fun get(index: String) = publicKeys.get(index)
}
