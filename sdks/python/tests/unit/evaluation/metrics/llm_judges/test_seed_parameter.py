"""
Test suite for seed parameter functionality in LLM judge metrics.

This module tests that the seed parameter is correctly implemented and passed
to the underlying model generation methods for all LLM judge metrics.
"""

import pytest
from unittest.mock import Mock, patch

from opik.evaluation.metrics.llm_judges.answer_relevance.metric import AnswerRelevance
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision
from opik.evaluation.metrics.llm_judges.context_recall.metric import ContextRecall
from opik.evaluation.metrics.llm_judges.g_eval.metric import GEval
from opik.evaluation.metrics.llm_judges.hallucination.metric import Hallucination
from opik.evaluation.metrics.llm_judges.moderation.metric import Moderation
from opik.evaluation.metrics.llm_judges.trajectory_accuracy.metric import (
    TrajectoryAccuracy,
)
from opik.evaluation.metrics.llm_judges.usefulness.metric import Usefulness
from opik.evaluation.metrics.llm_judges.structure_output_compliance.metric import (
    StructuredOutputCompliance,
)
from opik.evaluation.models import base_model
from opik.evaluation.metrics import score_result


class TestSeedParameter:
    """Test suite for seed parameter functionality in LLM judge metrics."""

    @pytest.fixture
    def test_seed(self) -> int:
        """Test seed value."""
        return 42

    def test_answer_relevance_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that AnswerRelevance passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.answer_relevance.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = AnswerRelevance(seed=test_seed, track=False)

            # Mock the parser to avoid parsing issues
            with patch(
                "opik.evaluation.metrics.llm_judges.answer_relevance.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                    context=["France is a country in Europe."],
                )

                # Verify seed was passed to model factory during initialization
                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_context_precision_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that ContextPrecision passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.context_precision.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = ContextPrecision(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.context_precision.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                    expected_output="Paris",
                    context=["France is a country in Europe."],
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_context_recall_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that ContextRecall passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.context_recall.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = ContextRecall(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.context_recall.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                    expected_output="Paris",
                    context=["France is a country in Europe."],
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_g_eval_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that GEval passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.g_eval.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = GEval(
                task_introduction="Evaluate the quality of the response.",
                evaluation_criteria="Check for accuracy and completeness.",
                seed=test_seed,
                track=False,
            )

            with patch(
                "opik.evaluation.metrics.llm_judges.g_eval.parser.parse_model_output_string"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(output="This is a test response.")

                # GEval calls generate_string multiple times (for chain of thought and evaluation)
                assert mock_model.generate_string.call_count >= 1

                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_hallucination_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that Hallucination passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.hallucination.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = Hallucination(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.hallucination.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="London is the capital of France.",
                    context=["The capital of France is Paris."],
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_moderation_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that Moderation passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.moderation.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = Moderation(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.moderation.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(output="This is a test message.")

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_trajectory_accuracy_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that TrajectoryAccuracy passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.trajectory_accuracy.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = TrajectoryAccuracy(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.trajectory_accuracy.parser.parse_evaluation_response"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                trajectory = [
                    {
                        "thought": "I need to search for information",
                        "action": "search(query='test')",
                        "observation": "Found relevant information",
                    }
                ]

                result = metric.score(
                    goal="Find information about test",
                    trajectory=trajectory,
                    final_result="Successfully found information",
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_usefulness_seed_parameter_passing(self, test_seed: int) -> None:
        """Test that Usefulness passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.usefulness.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = Usefulness(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.usefulness.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_structured_output_compliance_seed_parameter_passing(
        self, test_seed: int
    ) -> None:
        """Test that StructuredOutputCompliance passes seed parameter to model generation."""
        with patch(
            "opik.evaluation.metrics.llm_judges.structure_output_compliance.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = StructuredOutputCompliance(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.structure_output_compliance.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(output='{"name": "John", "age": 30}')

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

                assert isinstance(result, score_result.ScoreResult)

    def test_seed_parameter_none_behavior(self) -> None:
        """Test that metrics work correctly when seed is None."""
        with patch(
            "opik.evaluation.metrics.llm_judges.answer_relevance.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = AnswerRelevance(seed=None, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.answer_relevance.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                    context=["France is a country in Europe."],
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory during initialization
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") is None

                assert isinstance(result, score_result.ScoreResult)

    def test_seed_parameter_default_behavior(self) -> None:
        """Test that metrics work correctly when seed is not provided (default None)."""
        with patch(
            "opik.evaluation.metrics.llm_judges.answer_relevance.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = AnswerRelevance(track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.answer_relevance.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                result = metric.score(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                    context=["France is a country in Europe."],
                )

                mock_model.generate_string.assert_called_once()
                # Check that the seed was passed to the model factory during initialization
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") is None

                assert isinstance(result, score_result.ScoreResult)

    def test_all_metrics_accept_seed_parameter(self, test_seed: int) -> None:
        """Test that all LLM judge metrics accept seed parameter in constructor."""
        with (
            patch(
                "opik.evaluation.metrics.llm_judges.answer_relevance.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.context_precision.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.context_recall.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.g_eval.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.hallucination.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.moderation.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.trajectory_accuracy.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.usefulness.metric.models_factory.get"
            ),
            patch(
                "opik.evaluation.metrics.llm_judges.structure_output_compliance.metric.models_factory.get"
            ),
        ):
            metrics = [
                AnswerRelevance(seed=test_seed, track=False),
                ContextPrecision(seed=test_seed, track=False),
                ContextRecall(seed=test_seed, track=False),
                GEval(
                    task_introduction="Test task",
                    evaluation_criteria="Test criteria",
                    seed=test_seed,
                    track=False,
                ),
                Hallucination(seed=test_seed, track=False),
                Moderation(seed=test_seed, track=False),
                TrajectoryAccuracy(seed=test_seed, track=False),
                Usefulness(seed=test_seed, track=False),
                StructuredOutputCompliance(seed=test_seed, track=False),
            ]

            # All metrics should be created successfully
            assert len(metrics) == 9
            for metric in metrics:
                assert metric is not None

    @pytest.mark.asyncio
    async def test_async_methods_pass_seed_parameter(self, test_seed: int) -> None:
        """Test that async methods pass seed parameter to agenerate_string."""
        with patch(
            "opik.evaluation.metrics.llm_judges.answer_relevance.metric.models_factory.get"
        ) as mock_factory:
            mock_model = Mock(spec=base_model.OpikBaseModel)
            mock_factory.return_value = mock_model

            metric = AnswerRelevance(seed=test_seed, track=False)

            with patch(
                "opik.evaluation.metrics.llm_judges.answer_relevance.parser.parse_model_output"
            ) as mock_parser:
                mock_parser.return_value = score_result.ScoreResult(
                    name="test", value=0.8, reason="Test reason"
                )

                await metric.ascore(
                    input="What is the capital of France?",
                    output="Paris is the capital of France.",
                    context=["France is a country in Europe."],
                )

                mock_model.agenerate_string.assert_called_once()
                # Check that the seed was passed to the model factory
                mock_factory.assert_called_once()
                factory_call_kwargs = mock_factory.call_args[1]
                assert factory_call_kwargs.get("seed") == test_seed

    def test_seed_parameter_documentation(self) -> None:
        """Test that seed parameter is properly documented in docstrings."""

        metrics_with_seed = [
            AnswerRelevance,
            ContextPrecision,
            ContextRecall,
            GEval,
            Hallucination,
            Moderation,
            TrajectoryAccuracy,
            Usefulness,
            StructuredOutputCompliance,
        ]

        for metric_class in metrics_with_seed:
            docstring = metric_class.__doc__
            if docstring is not None:  # Only check if docstring exists
                assert "seed" in docstring.lower()
                assert (
                    "reproducible" in docstring.lower()
                    or "deterministic" in docstring.lower()
                )

    def test_seed_parameter_type_hints(self) -> None:
        """Test that seed parameter has correct type hints."""
        import inspect

        # Check AnswerRelevance as an example
        sig = inspect.signature(AnswerRelevance.__init__)
        seed_param = sig.parameters.get("seed")

        assert seed_param is not None
        assert seed_param.default is None

        # Check the annotation
        annotation = seed_param.annotation
        assert annotation is not None
        # The annotation should be Optional[int] or Union[int, None]
        assert "int" in str(annotation)
