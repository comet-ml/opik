import opik.guardrails.rules.custom_guardrail as custom_guardrail
import opik.guardrails.rules.rule as rule_module
import opik.guardrails.schemas as schemas


def test_custom_guardrail__default_threshold():
    custom_rule = custom_guardrail.CustomGuardrail(model_name="toxicity-v1")

    configs = custom_rule.get_validation_configs()

    assert len(configs) == 1
    assert configs[0] == {
        "type": schemas.ValidationType.CUSTOM_CLASSIFIER,
        "config": {
            "model_name": "toxicity-v1",
            "threshold": 0.5,
        },
    }


def test_custom_guardrail__custom_threshold():
    custom_rule = custom_guardrail.CustomGuardrail(
        model_name="toxicity-v1", threshold=0.8
    )

    configs = custom_rule.get_validation_configs()

    assert configs[0]["config"]["threshold"] == 0.8
    assert configs[0]["config"]["model_name"] == "toxicity-v1"


def test_custom_guardrail__runs_remotely():
    custom_rule = custom_guardrail.CustomGuardrail(model_name="toxicity-v1")

    assert custom_rule.location == rule_module.RuleLocation.GUARDRAILS_SERVICE
