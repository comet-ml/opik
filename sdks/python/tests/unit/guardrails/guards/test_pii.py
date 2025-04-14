import opik.guardrails.guards.pii as pii
import opik.guardrails.schemas as schemas


def test_pii__happyflow():
    guard = pii.PII(
        blocked_entities=["PERSON", "PHONE_NUMBER"], language="es", threshold=0.3
    )

    configs = guard.get_validation_configs()

    assert len(configs) == 1
    assert configs[0] == {
        "type": schemas.ValidationType.PII,
        "config": {
            "entities": ["PERSON", "PHONE_NUMBER"],
            "language": "es",
            "threshold": 0.3,
        },
    }


def test_pii__entities_not_set__None_config_value():
    guard = pii.PII(language="es", threshold=0.3)

    configs = guard.get_validation_configs()

    assert len(configs) == 1
    assert configs[0] == {
        "type": schemas.ValidationType.PII,
        "config": {
            "entities": None,
            "language": "es",
            "threshold": 0.3,
        },
    }
