import opik.guardrails.rules.pii as pii
import opik.guardrails.schemas as schemas


def test_pii__happyflow():
    pii_rule = pii.PII(entities=["PERSON", "PHONE_NUMBER"])

    configs = pii_rule.get_validation_configs()

    assert configs == [
        {
            "type": schemas.ValidationType.PII,
            "config": {"entities": ["PERSON", "PHONE_NUMBER"]},
        }
    ]
