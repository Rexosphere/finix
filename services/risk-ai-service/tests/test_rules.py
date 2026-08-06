from app.rules import Decision, decide, rules_engine


def test_decide_thresholds():
    assert decide(0) is Decision.ALLOW
    assert decide(39) is Decision.ALLOW
    assert decide(40) is Decision.STEP_UP
    assert decide(70) is Decision.STEP_UP
    assert decide(71) is Decision.BLOCK


def test_rules_high_amount_and_velocity():
    score, reasons = rules_engine(
        amount_minor=600_000_00,
        velocity_1h=9,
        new_device=True,
        geo_velocity=3.0,
        payee_novelty=True,
        offline_voucher=False,
        hour=2,
    )
    assert score >= 71
    assert "amount>=500000" in reasons
    assert "velocity>=8/h" in reasons
