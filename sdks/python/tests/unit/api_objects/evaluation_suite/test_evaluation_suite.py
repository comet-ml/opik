"""Unit tests for EvaluationSuite validation."""

import pytest
from unittest import mock

from opik.api_objects.evaluation_suite import evaluation_suite
from opik.evaluation.suite_evaluators import LLMJudge
from opik.evaluation import metrics


class TestEvaluatorValidation:
    def test_init__with_non_llm_judge_evaluator__raises_type_error(self):
        """Test that non-LLMJudge evaluators raise TypeError on suite creation."""
        mock_dataset = mock.MagicMock()
        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            evaluation_suite.EvaluationSuite(
                name="test_suite",
                dataset_=mock_dataset,
                evaluators=[equals_metric],
            )

        assert "Evaluation suites only support LLMJudge evaluators" in str(exc_info.value)
        assert "Equals" in str(exc_info.value)

    def test_init__with_llm_judge_evaluator__succeeds(self):
        """Test that LLMJudge evaluators are accepted."""
        mock_dataset = mock.MagicMock()
        llm_judge = LLMJudge(
            assertions=["Response is helpful"],
            track=False,
        )

        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
            evaluators=[llm_judge],
        )

        assert suite.evaluators == [llm_judge]

    def test_init__with_no_evaluators__succeeds(self):
        """Test that suite can be created without evaluators."""
        mock_dataset = mock.MagicMock()

        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        assert suite.evaluators == []

    def test_add_item__with_non_llm_judge_evaluator__raises_type_error(self):
        """Test that non-LLMJudge evaluators raise TypeError on add_item."""
        mock_dataset = mock.MagicMock()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            suite.add_item(
                data={"input": "test"},
                evaluators=[equals_metric],
            )

        assert "Evaluation suites only support LLMJudge evaluators" in str(exc_info.value)
        assert "item-level evaluators" in str(exc_info.value)

    def test_add_item__with_llm_judge_evaluator__succeeds(self):
        """Test that LLMJudge evaluators are accepted in add_item."""
        mock_dataset = mock.MagicMock()
        mock_dataset.__internal_api__insert_items_as_dataclasses__ = mock.MagicMock()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        llm_judge = LLMJudge(
            assertions=["Response is polite"],
            track=False,
        )

        # Should not raise
        suite.add_item(
            data={"input": "test"},
            evaluators=[llm_judge],
        )

    def test_add_item__with_no_evaluators__succeeds(self):
        """Test that items can be added without evaluators."""
        mock_dataset = mock.MagicMock()
        mock_dataset.__internal_api__insert_items_as_dataclasses__ = mock.MagicMock()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        # Should not raise
        suite.add_item(data={"input": "test"})

    def test_init__with_mixed_evaluators__raises_type_error(self):
        """Test that mixing LLMJudge with other evaluators raises TypeError."""
        mock_dataset = mock.MagicMock()
        llm_judge = LLMJudge(assertions=["Test"], track=False)
        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            evaluation_suite.EvaluationSuite(
                name="test_suite",
                dataset_=mock_dataset,
                evaluators=[llm_judge, equals_metric],
            )

        assert "Evaluation suites only support LLMJudge evaluators" in str(exc_info.value)
