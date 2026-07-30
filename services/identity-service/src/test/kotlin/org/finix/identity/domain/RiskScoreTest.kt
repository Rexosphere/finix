package org.finix.identity.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RiskScoreTest : StringSpec({

    "scores at or above the threshold require step-up" {
        RiskScore.of(RiskScore.STEP_UP_THRESHOLD).requireStepUp shouldBe true
        RiskScore.of(RiskScore.STEP_UP_THRESHOLD - 1).requireStepUp shouldBe false
    }

    "of clamps into 0..100" {
        RiskScore.of(200).score shouldBe Device.MAX_TRUST
        RiskScore.of(-5).score shouldBe Device.MIN_TRUST
    }
})
