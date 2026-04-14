"""Unit tests for test_suite.converters module."""

from unittest import mock


from opik.api_objects.dataset import dataset_item
from opik.api_objects.dataset.test_suite import converters


class TestEvaluatorsToAssertions:
    def test_extracts_assertions_from_evaluators(self):
        evaluator = mock.MagicMock()
        evaluator.assertions = ["Is polite", "Is helpful"]

        result = converters.evaluators_to_assertions([evaluator])

        assert result == ["Is polite", "Is helpful"]

    def test_multiple_evaluators__concatenates_assertions(self):
        e1 = mock.MagicMock()
        e1.assertions = ["A1"]
        e2 = mock.MagicMock()
        e2.assertions = ["A2", "A3"]

        result = converters.evaluators_to_assertions([e1, e2])

        assert result == ["A1", "A2", "A3"]

    def test_empty_list__returns_empty(self):
        assert converters.evaluators_to_assertions([]) == []


class TestVersionEvaluatorsToAssertions:
    def test_extracts_assertions_from_llm_judge_evaluator(self):
        from opik.evaluation.suite_evaluators import LLMJudge

        judge = LLMJudge(assertions=["Response is accurate"], track=False)
        config = judge.to_config().model_dump(by_alias=True)

        evaluator_item = mock.MagicMock()
        evaluator_item.type = "llm_judge"
        evaluator_item.config = config

        result = converters.version_evaluators_to_assertions([evaluator_item])

        assert result == ["Response is accurate"]

    def test_none_evaluators__returns_empty(self):
        assert converters.version_evaluators_to_assertions(None) == []

    def test_empty_list__returns_empty(self):
        assert converters.version_evaluators_to_assertions([]) == []

    def test_non_llm_judge_evaluator__skipped(self):
        evaluator_item = mock.MagicMock()
        evaluator_item.type = "custom_scorer"

        result = converters.version_evaluators_to_assertions([evaluator_item])

        assert result == []


class TestVersionPolicyToExecutionPolicy:
    def test_converts_policy(self):
        policy = mock.MagicMock()
        policy.runs_per_item = 5
        policy.pass_threshold = 3

        result = converters.version_policy_to_execution_policy(policy)

        assert result == {"runs_per_item": 5, "pass_threshold": 3}

    def test_none_policy__returns_default(self):
        result = converters.version_policy_to_execution_policy(None)

        assert result == {"runs_per_item": 1, "pass_threshold": 1}

    def test_none_fields__default_to_1(self):
        policy = mock.MagicMock()
        policy.runs_per_item = None
        policy.pass_threshold = None

        result = converters.version_policy_to_execution_policy(policy)

        assert result == {"runs_per_item": 1, "pass_threshold": 1}


class TestDatasetItemToSuiteItemDict:
    def test_item_without_evaluators(self):
        item = dataset_item.DatasetItem(
            id="item-1",
            description="Test item",
            question="What is 2+2?",
        )

        result = converters.dataset_item_to_suite_item_dict(item)

        assert result["id"] == "item-1"
        assert result["data"]["question"] == "What is 2+2?"
        assert result["description"] == "Test item"
        assert result["assertions"] == []
        assert "execution_policy" not in result

    def test_item_with_llm_judge_evaluator(self):
        from opik.evaluation.suite_evaluators import LLMJudge

        judge = LLMJudge(assertions=["Is correct"], track=False)
        config = judge.to_config().model_dump(by_alias=True)

        item = dataset_item.DatasetItem(
            id="item-2",
            evaluators=[
                dataset_item.EvaluatorItem(
                    name="llm_judge",
                    type="llm_judge",
                    config=config,
                ),
            ],
            question="Hello",
        )

        result = converters.dataset_item_to_suite_item_dict(item)

        assert result["assertions"] == ["Is correct"]

    def test_item_with_execution_policy(self):
        item = dataset_item.DatasetItem(
            id="item-3",
            execution_policy=dataset_item.ExecutionPolicyItem(
                runs_per_item=3,
                pass_threshold=2,
            ),
            question="Test",
        )

        result = converters.dataset_item_to_suite_item_dict(item)

        assert result["execution_policy"]["runs_per_item"] == 3
        assert result["execution_policy"]["pass_threshold"] == 2
