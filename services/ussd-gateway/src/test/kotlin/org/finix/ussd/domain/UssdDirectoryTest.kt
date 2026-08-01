package org.finix.ussd.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class UssdDirectoryTest : StringSpec({

    "normalize accepts international, trunk, and local forms" {
        UssdDirectory.normalize("+94771110001") shouldBe "+94771110001"
        UssdDirectory.normalize("94771110001") shouldBe "+94771110001"
        UssdDirectory.normalize("0771110001") shouldBe "+94771110001"
        UssdDirectory.normalize("771110001") shouldBe "+94771110001"
    }

    "findByPhone resolves seeded personas" {
        UssdDirectory.findByPhone("0771110001") shouldBe UssdDirectory.FARMER
        UssdDirectory.findByPhone("+94771110002") shouldBe UssdDirectory.SME
        UssdDirectory.findByPhone("999").shouldBeNull()
    }

    "findByAccountNumber is case-insensitive" {
        UssdDirectory.findByAccountNumber("finix-sav-00000001").shouldNotBeNull()
        UssdDirectory.findByAccountNumber("UNKNOWN").shouldBeNull()
    }
})
