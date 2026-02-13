from opik.evaluation.suite_evaluators import LLMJudge
from opik.evaluation.suite_evaluators.opik_llm_judge_config import (
    LLMJudgeConfig,
    LLMJudgeModelConfig,
    LLMJudgeSchemaItem,
    LLMJudgeMessage,
)


class TestLLMJudgeInit:
    def test_init__with_string_assertions__stores_texts(self):
        """Test that string assertions are stored directly."""
        evaluator = LLMJudge(
            assertions=[
                "Response is factually correct",
                "No hallucinated information",
            ],
            track=False,
        )

        assert len(evaluator.assertions) == 2
        assert evaluator.assertions[0] == "Response is factually correct"
        assert evaluator.assertions[1] == "No hallucinated information"

    def test_init__with_custom_name__uses_custom_name(self):
        evaluator = LLMJudge(
            assertions=["Test assertion"],
            name="custom_evaluator",
            track=False,
        )

        assert evaluator.name == "custom_evaluator"

    def test_init__with_track_false__sets_track(self):
        evaluator = LLMJudge(
            assertions=["Test assertion"],
            track=False,
        )

        assert evaluator.track is False

    def test_init__with_track_true__sets_track(self):
        evaluator = LLMJudge(
            assertions=["Test assertion"],
            track=True,
        )

        assert evaluator.track is True

    def test_assertions_property__returns_copy__modifications_dont_affect_original(
        self,
    ):
        evaluator = LLMJudge(
            assertions=["Test assertion"],
            track=False,
        )

        assertions = evaluator.assertions
        assertions.append("Another assertion")

        assert len(evaluator.assertions) == 1


class TestLLMJudgeToConfig:
    def test_to_config__basic__returns_valid_config(self):
        evaluator = LLMJudge(
            assertions=[
                "Response is accurate",
                "Response is helpful",
            ],
            model="gpt-4o",
            temperature=0.0,
            seed=123,
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert config_dict["name"] == "llm_judge"
        assert config_dict["model"] == {
            "name": "gpt-4o",
            "temperature": 0.0,
            "seed": 123,
        }
        assert config_dict["variables"] == {"input": "input", "output": "output"}
        # Both name and description use the assertion text directly
        assert config_dict["schema"] == [
            {
                "name": "Response is accurate",
                "type": "BOOLEAN",
                "description": "Response is accurate",
            },
            {
                "name": "Response is helpful",
                "type": "BOOLEAN",
                "description": "Response is helpful",
            },
        ]
        # Verify assertions are listed in the prompt (response format is via structured output)
        assert "- Response is accurate" in config_dict["messages"][0]["content"]
        assert "- Response is helpful" in config_dict["messages"][0]["content"]

    def test_to_config__without_optional_params__uses_defaults(self):
        evaluator = LLMJudge(
            assertions=["Test"],
            track=False,
        )

        config = evaluator.to_config()

        # Temperature defaults to 0.0 when not specified (backend requires it)
        assert config.model.temperature == 0.0
        assert config.model.seed is None

    def test_to_config__serializes_to_dict_with_schema_alias(self):
        evaluator = LLMJudge(
            assertions=["Test"],
            model="gpt-4o",
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert "schema" in config_dict
        assert "schema_" not in config_dict


class TestLLMJudgeFromConfig:
    def test_from_config__valid_config__creates_evaluator(self):
        config = LLMJudgeConfig(
            model=LLMJudgeModelConfig(name="gpt-4o", temperature=0.5, seed=42),
            variables={"input": "input", "output": "output"},
            schema=[
                LLMJudgeSchemaItem(
                    name="accurate", type="BOOLEAN", description="Is accurate"
                ),
                LLMJudgeSchemaItem(
                    name="helpful", type="BOOLEAN", description="Is helpful"
                ),
            ],
            messages=[],
        )

        evaluator = LLMJudge.from_config(config, name="restored_evaluator", track=False)

        assert evaluator.name == "restored_evaluator"
        # from_config extracts description as assertion texts
        assert evaluator.assertions[0] == "Is accurate"
        assert evaluator.assertions[1] == "Is helpful"

    def test_from_config__roundtrip__preserves_assertions(self):
        original = LLMJudge(
            assertions=[
                "Factually correct",
                "Relevant to question",
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
        # Assertions should be preserved
        assert restored.assertions[0] == "Factually correct"
        assert restored.assertions[1] == "Relevant to question"

    def test_from_config__config_model_params__preserved_in_new_config(self):
        config = LLMJudgeConfig(
            model=LLMJudgeModelConfig(name="gpt-4o-mini", temperature=0.7, seed=123),
            variables={"input": "input", "output": "output"},
            schema=[
                LLMJudgeSchemaItem(name="test", type="BOOLEAN", description="Test"),
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
