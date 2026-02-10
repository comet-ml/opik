import pytest

from opik.evaluation.suite_evaluators import LLMJudge
from opik.evaluation.suite_evaluators.opik_llm_judge_config import (
    LLMJudgeConfig,
    LLMJudgeModelConfig,
    LLMJudgeSchemaItem,
    LLMJudgeMessage,
)


class TestLLMJudgeInit:
    def test_init__with_assertions_list__stores_assertions(self):
        assertions = [
            {"name": "test", "expected_behavior": "Test assertion"},
        ]
        evaluator = LLMJudge(assertions=assertions, track=False)

        assert evaluator.assertions == assertions
        assert evaluator.name == "llm_judge"

    def test_init__with_custom_name__uses_custom_name(self):
        evaluator = LLMJudge(
            assertions=[{"name": "a", "expected_behavior": "b"}],
            name="custom_evaluator",
            track=False,
        )

        assert evaluator.name == "custom_evaluator"

    def test_init__with_track_false__sets_track(self):
        evaluator = LLMJudge(
            assertions=[{"name": "a", "expected_behavior": "b"}],
            track=False,
        )

        assert evaluator.track is False

    def test_init__with_track_true__sets_track(self):
        evaluator = LLMJudge(
            assertions=[{"name": "a", "expected_behavior": "b"}],
            track=True,
        )

        assert evaluator.track is True

    def test_assertions_property__returns_copy__modifications_dont_affect_original(self):
        evaluator = LLMJudge(
            assertions=[{"name": "a", "expected_behavior": "b"}],
            track=False,
        )

        assertions = evaluator.assertions
        assertions.append({"name": "c", "expected_behavior": "d"})

        assert len(evaluator.assertions) == 1


class TestLLMJudgeToConfig:
    def test_to_config__basic__returns_valid_config(self):
        assertions = [
            {"name": "accurate", "expected_behavior": "Response is accurate"},
            {"name": "helpful", "expected_behavior": "Response is helpful"},
        ]
        evaluator = LLMJudge(
            assertions=assertions,
            model="gpt-4o",
            temperature=0.0,
            seed=123,
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert config_dict == {
            "model": {"name": "gpt-4o", "temperature": 0.0, "seed": 123},
            "variables": {"input": "input", "output": "output"},
            "schema": [
                {
                    "name": "accurate",
                    "type": "BOOLEAN",
                    "expected_behavior": "Response is accurate",
                },
                {
                    "name": "helpful",
                    "type": "BOOLEAN",
                    "expected_behavior": "Response is helpful",
                },
            ],
            "messages": [{"role": "USER", "content": config_dict["messages"][0]["content"]}],
        }

    def test_to_config__without_optional_params__sets_none(self):
        evaluator = LLMJudge(
            assertions=[{"name": "test", "expected_behavior": "Test"}],
            track=False,
        )

        config = evaluator.to_config()

        assert config.model.temperature is None
        assert config.model.seed is None

    def test_to_config__serializes_to_dict_with_schema_alias(self):
        evaluator = LLMJudge(
            assertions=[{"name": "test", "expected_behavior": "Test"}],
            model="gpt-4o",
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert "schema" in config_dict
        assert "schema_" not in config_dict
        assert config_dict["schema"][0]["name"] == "test"


class TestLLMJudgeFromConfig:
    def test_from_config__valid_config__creates_evaluator(self):
        config = LLMJudgeConfig(
            model=LLMJudgeModelConfig(name="gpt-4o", temperature=0.5, seed=42),
            variables={"input": "input", "output": "output"},
            schema=[
                LLMJudgeSchemaItem(
                    name="accurate", type="BOOLEAN", expected_behavior="Is accurate"
                ),
                LLMJudgeSchemaItem(
                    name="helpful", type="BOOLEAN", expected_behavior="Is helpful"
                ),
            ],
            messages=[],
        )

        evaluator = LLMJudge.from_config(
            config, name="restored_evaluator", track=False
        )

        assert evaluator.name == "restored_evaluator"
        assert evaluator.assertions == [
            {"name": "accurate", "expected_behavior": "Is accurate"},
            {"name": "helpful", "expected_behavior": "Is helpful"},
        ]

    def test_from_config__roundtrip__preserves_data(self):
        original = LLMJudge(
            assertions=[
                {"name": "factual", "expected_behavior": "Factually correct"},
                {"name": "relevant", "expected_behavior": "Relevant to question"},
            ],
            model="gpt-4o",
            temperature=0.2,
            seed=999,
            name="my_evaluator",
            track=False,
        )

        config = original.to_config()
        restored = LLMJudge.from_config(config, name="my_evaluator", track=False)

        assert restored.name == original.name
        assert restored.assertions == original.assertions

    def test_from_config__config_model_params__preserved_in_new_config(self):
        config = LLMJudgeConfig(
            model=LLMJudgeModelConfig(name="gpt-4o-mini", temperature=0.7, seed=123),
            variables={"input": "input", "output": "output"},
            schema=[
                LLMJudgeSchemaItem(
                    name="test", type="BOOLEAN", expected_behavior="Test"
                ),
            ],
            messages=[LLMJudgeMessage(role="USER", content="test")],
        )

        evaluator = LLMJudge.from_config(config, name="test", track=False)
        new_config = evaluator.to_config()
        new_config_dict = new_config.model_dump(by_alias=True, exclude_none=True)

        assert new_config_dict["model"] == {
            "name": "gpt-4o-mini",
            "temperature": 0.7,
            "seed": 123,
        }
