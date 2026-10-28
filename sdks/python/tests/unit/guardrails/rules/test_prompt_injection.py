import opik.guardrails.rules.prompt_injection as prompt_injection
import opik.guardrails.rules.rule as rule_module
import opik.guardrails.schemas as schemas


def test_prompt_injection__happyflow():
    injection_rule = prompt_injection.PromptInjection()

    configs = injection_rule.get_validation_configs()

    assert configs == [{"type": schemas.ValidationType.PROMPT_INJECTION, "config": {}}]


def test_prompt_injection__checked_by_opik():
    injection_rule = prompt_injection.PromptInjection()

    assert injection_rule.location == rule_module.RuleLocation.OPIK
