import opik.guardrails.guards.prompt_injection as prompt_injection
import opik.guardrails.schemas as schemas


def test_prompt_injection__default_threshold():
    guard = prompt_injection.PromptInjection()

    configs = guard.get_validation_configs()

    assert len(configs) == 1
    assert configs[0] == {
        "type": schemas.ValidationType.PROMPT_INJECTION,
        "config": {
            "threshold": 0.5,
        },
    }


def test_prompt_injection__custom_threshold():
    guard = prompt_injection.PromptInjection(threshold=0.8)

    configs = guard.get_validation_configs()

    assert configs[0]["config"]["threshold"] == 0.8


def test_prompt_injection__runs_remotely():
    guard = prompt_injection.PromptInjection()

    assert guard.local is False
