package no.nav.sf.github.metrics

/**
 * runners' public keys must be here in order to allow forwarding
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
            """,
            "crm-workflows-base" to """
                MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAJuQ3vM/9ON/yztjF+5i79RMMW39
                JYZnWL2sy1xeABN+t2Pow34/cqhN4TbyRV54tAFn/FafAA5ZZDCVswRSmA==
            """,
            "crm-platform-base" to """
                MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKjKn/7mBRCGUHB7wZAbUnn4vtkRP
                BQxn5S0Nhe/KrdrG/uY6mWa6OOmuOTPQDNHVK1b/rv5KSaF0Wg/JHL6W2g==
            """,
        )
    }

    override fun get(index: String) = publicKeys.get(index)
}
