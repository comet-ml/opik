import pytest
from unittest import mock

from opik.evaluation.suite_evaluators import AssertionEvaluator
from opik.evaluation.suite_evaluators.assertion_evaluator import (
    AssertionEvaluatorConfig,
    AssertionEvaluatorModelConfig,
    AssertionEvaluatorSchemaItem,
)


class TestAssertionEvaluatorInit:
    def test_init__with_assertions__stores_assertions(self):
        assertions = [
            {"name": "test", "description": "Test assertion"},
        ]
        evaluator = AssertionEvaluator(assertions=assertions, track=False)

        assert evaluator._assertions == assertions
        assert evaluator.name == "assertion_evaluator"

    def test_init__with_custom_name__uses_custom_name(self):
        evaluator = AssertionEvaluator(
            assertions=[{"name": "a", "description": "b"}],
            name="custom_evaluator",
            track=False,
        )

        assert evaluator.name == "custom_evaluator"

    def test_init__with_model_params__stores_params(self):
        evaluator = AssertionEvaluator(
            assertions=[{"name": "a", "description": "b"}],
            model="gpt-4o",
            temperature=0.5,
            seed=42,
            track=False,
        )

        assert evaluator._temperature == 0.5
        assert evaluator._seed == 42
        assert evaluator._model_name == "gpt-4o"


class TestAssertionEvaluatorToConfig:
    def test_to_config__basic__returns_valid_config(self):
        assertions = [
            {"name": "accurate", "description": "Response is accurate"},
            {"name": "helpful", "description": "Response is helpful"},
        ]
        evaluator = AssertionEvaluator(
            assertions=assertions,
            model="gpt-4o",
            temperature=0.0,
            seed=123,
            track=False,
        )

        config = evaluator.to_config()

        assert config.model.name == "gpt-4o"
        assert config.model.temperature == 0.0
        assert config.model.seed == 123

        assert config.variables == {"input": "input", "output": "output"}

        assert len(config.schema_) == 2
        assert config.schema_[0].name == "accurate"
        assert config.schema_[0].type == "INTEGER"
        assert config.schema_[0].description == "Response is accurate"
        assert config.schema_[1].name == "helpful"

        assert len(config.messages) == 1
        assert config.messages[0].role == "USER"

    def test_to_config__without_optional_params__sets_none(self):
        evaluator = AssertionEvaluator(
            assertions=[{"name": "test", "description": "Test"}],
            track=False,
        )

        config = evaluator.to_config()

        assert config.model.temperature is None
        assert config.model.seed is None

    def test_to_config__serializes_to_dict_with_schema_alias(self):
        evaluator = AssertionEvaluator(
            assertions=[{"name": "test", "description": "Test"}],
            model="gpt-4o",
            track=False,
        )

        config = evaluator.to_config()
        config_dict = config.model_dump(by_alias=True, exclude_none=True)

        assert "schema" in config_dict
        assert "schema_" not in config_dict
        assert config_dict["schema"][0]["name"] == "test"


class TestAssertionEvaluatorFromConfig:
    def test_from_config__valid_config__creates_evaluator(self):
        config = AssertionEvaluatorConfig(
            model=AssertionEvaluatorModelConfig(name="gpt-4o", temperature=0.5, seed=42),
            variables={"input": "input", "output": "output"},
            schema=[
                AssertionEvaluatorSchemaItem(name="accurate", type="INTEGER", description="Is accurate"),
                AssertionEvaluatorSchemaItem(name="helpful", type="INTEGER", description="Is helpful"),
            ],
            messages=[],
        )

        evaluator = AssertionEvaluator.from_config(config, name="restored_evaluator", track=False)

        assert evaluator.name == "restored_evaluator"
        assert evaluator._temperature == 0.5
        assert evaluator._seed == 42
        assert len(evaluator._assertions) == 2
        assert evaluator._assertions[0]["name"] == "accurate"
        assert evaluator._assertions[0]["description"] == "Is accurate"

    def test_from_config__roundtrip__preserves_data(self):
        original = AssertionEvaluator(
            assertions=[
                {"name": "factual", "description": "Factually correct"},
                {"name": "relevant", "description": "Relevant to question"},
            ],
            model="gpt-4o",
            temperature=0.2,
            seed=999,
            name="my_evaluator",
            track=False,
        )

        config = original.to_config()
        restored = AssertionEvaluator.from_config(config, name="my_evaluator", track=False)

        assert restored.name == original.name
        assert restored._temperature == original._temperature
        assert restored._seed == original._seed
        assert len(restored._assertions) == len(original._assertions)
        for orig, rest in zip(original._assertions, restored._assertions):
            assert orig["name"] == rest["name"]
            assert orig["description"] == rest["description"]


class TestAssertionEvaluatorScore:
    def test_score__mocked_model__returns_results(self):
        mock_model = mock.MagicMock()
        mock_model.generate_string.return_value = """
        {
            "results": [
                {"name": "accurate", "value": 1, "reason": "Correct", "metadata": {"pass_score": 0.95}}
            ]
        }
        """

        evaluator = AssertionEvaluator(
            assertions=[{"name": "accurate", "description": "Is accurate"}],
            model=mock_model,
            track=False,
        )

        results = evaluator.score(input="What is 2+2?", output="4")

        assert len(results) == 1
        assert results[0].name == "assertion_evaluator_accurate"
        assert results[0].value == 1.0
        assert results[0].reason == "Correct"
        assert results[0].metadata == {
            "pass_score": 0.95,
            "assertion_text": "Is accurate",
        }
        mock_model.generate_string.assert_called_once()

    def test_score__multiple_assertions__returns_multiple_results(self):
        mock_model = mock.MagicMock()
        mock_model.generate_string.return_value = """
        {
            "results": [
                {"name": "accurate", "value": 1, "reason": "OK", "metadata": {"pass_score": 1.0}},
                {"name": "helpful", "value": 0, "reason": "Not helpful", "metadata": {"pass_score": 0.2}}
            ]
        }
        """

        evaluator = AssertionEvaluator(
            assertions=[
                {"name": "accurate", "description": "Is accurate"},
                {"name": "helpful", "description": "Is helpful"},
            ],
            model=mock_model,
            track=False,
        )

        results = evaluator.score(input="test", output="test")

        assert len(results) == 2
        assert results[0].value == 1.0
        assert results[0].metadata["assertion_text"] == "Is accurate"
        assert results[1].value == 0.0
        assert results[1].metadata["assertion_text"] == "Is helpful"


class TestAssertionEvaluatorAsync:
    @pytest.mark.asyncio
    async def test_ascore__mocked_model__returns_results(self):
        mock_model = mock.MagicMock()
        mock_model.agenerate_string = mock.AsyncMock(
            return_value="""
            {
                "results": [
                    {"name": "test", "value": 1, "reason": "Pass", "metadata": {"pass_score": 0.9}}
                ]
            }
            """
        )

        evaluator = AssertionEvaluator(
            assertions=[{"name": "test", "description": "Test assertion"}],
            model=mock_model,
            track=False,
        )

        results = await evaluator.ascore(input="input", output="output")

        assert len(results) == 1
        assert results[0].value == 1.0
        assert results[0].metadata == {
            "pass_score": 0.9,
            "assertion_text": "Test assertion",
        }
        mock_model.agenerate_string.assert_called_once()
