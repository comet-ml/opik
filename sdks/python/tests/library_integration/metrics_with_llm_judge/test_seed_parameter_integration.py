"""
Integration tests for seed parameter behavior in LLM judge metrics.

This module tests the behavioral aspects of the seed parameter, specifically:
1. Deterministic results with the same seed across multiple runs
2. Different results with different seeds
3. Consistency of results when using the same seed
"""

import pytest
from typing import Dict, Any
import time

from opik.evaluation import metrics
from ...testlib import assert_helpers
import langchain_openai
from opik.evaluation.models.langchain import langchain_chat_model


pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


class TestSeedParameterIntegration:
    """Integration tests for seed parameter behavior in LLM judge metrics."""

    @pytest.fixture
    def test_inputs(self) -> Dict[str, Any]:
        """Standard test inputs for consistency across tests."""
        return {
            "input": "What is the capital of France?",
            "output": "Paris is the capital of France.",
            "context": ["France is a country in Europe.", "Paris is the capital city."],
            "expected_output": "Paris",
        }

    @pytest.fixture
    def model(self) -> str:
        """Standard model for testing."""
        return "gpt-4o"

    def test_same_seed_produces_consistent_results(
        self, test_inputs: Dict[str, Any], model: str
    ) -> None:
        """Test that the same seed produces identical results across multiple runs."""
        seed = 42
        num_runs = 3

        # Test with AnswerRelevance metric
        metric = metrics.AnswerRelevance(model=model, seed=seed, track=False)

        results = []
        for _ in range(num_runs):
            result = metric.score(
                input=test_inputs["input"],
                output=test_inputs["output"],
                context=test_inputs["context"],
            )
            results.append(result)
            # Small delay to ensure different timestamps don't affect results
            time.sleep(0.1)

        # All results should be identical
        first_result = results[0]
        for result in results[1:]:
            assert (
                result.value == first_result.value
            ), "Same seed should produce identical values"
            assert (
                result.reason == first_result.reason
            ), "Same seed should produce identical reasons"
            assert (
                result.name == first_result.name
            ), "Same seed should produce identical names"

    def test_different_seeds_can_produce_different_results(
        self, test_inputs: Dict[str, Any], model: str
    ) -> None:
        """Test that different seeds can produce different results."""
        seeds = [42, 123, 999]

        metric_class = metrics.AnswerRelevance
        results = []

        for seed in seeds:
            metric = metric_class(model=model, seed=seed, track=False)
            result = metric.score(
                input=test_inputs["input"],
                output=test_inputs["output"],
                context=test_inputs["context"],
            )
            results.append((seed, result))

        # Check that at least some results are different
        # (Note: This is probabilistic - different seeds *can* produce same results)
        values = [result.value for _, result in results]
        reasons = [result.reason for _, result in results]

        # At least one of values or reasons should show variation
        values_unique = len(set(values)) > 1
        reasons_unique = len(set(reasons)) > 1

        # This assertion might occasionally fail due to randomness, but should generally pass
        assert (
            values_unique or reasons_unique
        ), "Different seeds should generally produce different results"

    def test_seed_consistency_across_metric_types(
        self, test_inputs: Dict[str, Any], model: str
    ) -> None:
        """Test that seed behavior is consistent across different metric types."""
        seed = 42
        num_runs = 2

        # Test multiple metric types
        metric_configs = [
            (
                metrics.AnswerRelevance,
                {
                    "input": test_inputs["input"],
                    "output": test_inputs["output"],
                    "context": test_inputs["context"],
                },
            ),
            (
                metrics.ContextPrecision,
                {
                    "input": test_inputs["input"],
                    "output": test_inputs["output"],
                    "expected_output": test_inputs["expected_output"],
                    "context": test_inputs["context"],
                },
            ),
            (
                metrics.ContextRecall,
                {
                    "input": test_inputs["input"],
                    "output": test_inputs["output"],
                    "expected_output": test_inputs["expected_output"],
                    "context": test_inputs["context"],
                },
            ),
            (
                metrics.Usefulness,
                {
                    "input": test_inputs["input"],
                    "output": test_inputs["output"],
                },
            ),
        ]

        for metric_class, score_kwargs in metric_configs:
            results = []

            for _ in range(num_runs):
                metric = metric_class(model=model, seed=seed, track=False)
                result = metric.score(**score_kwargs)
                results.append(result)
                time.sleep(0.1)

            # Results should be consistent within each metric type
            first_result = results[0]
            for result in results[1:]:
                assert (
                    result.value == first_result.value
                ), f"Same seed should produce identical values for {metric_class.__name__}"
                assert (
                    result.reason == first_result.reason
                ), f"Same seed should produce identical reasons for {metric_class.__name__}"

    def test_seed_none_vs_seed_integer_behavior(
        self, test_inputs: Dict[str, Any], model: str
    ) -> None:
        """Test behavioral differences between seed=None and seed=integer."""
        # Test with seed=None (should be non-deterministic)
        metric_none = metrics.AnswerRelevance(model=model, seed=None, track=False)

        # Test with seed=42 (should be deterministic)
        metric_seeded = metrics.AnswerRelevance(model=model, seed=42, track=False)

        # Run multiple times with seed=None
        results_none = []
        for _ in range(3):
            result = metric_none.score(
                input=test_inputs["input"],
                output=test_inputs["output"],
                context=test_inputs["context"],
            )
            results_none.append(result)
            time.sleep(0.1)

        # Run multiple times with seed=42
        results_seeded = []
        for _ in range(3):
            result = metric_seeded.score(
                input=test_inputs["input"],
                output=test_inputs["output"],
                context=test_inputs["context"],
            )
            results_seeded.append(result)
            time.sleep(0.1)

        # Seeded results should be identical
        seeded_values = [r.value for r in results_seeded]
        assert len(set(seeded_values)) == 1, "Seeded results should be identical"

        # None-seeded results might be different (though not guaranteed)
        # We can't assert they're different because they might coincidentally be the same
        # But we can verify the seeded results are consistent
        assert all(
            v == seeded_values[0] for v in seeded_values
        ), "All seeded results should be identical"

    def test_g_eval_seed_consistency(self, model: str) -> None:
        """Test seed consistency for GEval metric which has more complex generation."""
        seed = 42
        num_runs = 2

        metric = metrics.GEval(
            task_introduction="Evaluate the quality of this response.",
            evaluation_criteria="Check for accuracy, completeness, and clarity.",
            model=model,
            seed=seed,
            track=False,
        )

        results = []
        for _ in range(num_runs):
            result = metric.score(output="This is a test response for evaluation.")
            results.append(result)
            time.sleep(0.1)

        # GEval results should be consistent with same seed
        first_result = results[0]
        for result in results[1:]:
            assert (
                result.value == first_result.value
            ), "GEval with same seed should produce identical values"
            assert (
                result.reason == first_result.reason
            ), "GEval with same seed should produce identical reasons"

    def test_structured_output_compliance_seed_consistency(self, model: str) -> None:
        """Test seed consistency for StructuredOutputCompliance metric."""
        seed = 42
        num_runs = 2

        metric = metrics.StructuredOutputCompliance(
            model=model,
            seed=seed,
            track=False,
        )

        results = []
        for _ in range(num_runs):
            result = metric.score(
                output='{"name": "John", "age": 30, "city": "New York"}'
            )
            results.append(result)
            time.sleep(0.1)

        # StructuredOutputCompliance results should be consistent with same seed
        first_result = results[0]
        for result in results[1:]:
            assert (
                result.value == first_result.value
            ), "StructuredOutputCompliance with same seed should produce identical values"
            assert (
                result.reason == first_result.reason
            ), "StructuredOutputCompliance with same seed should produce identical reasons"

    def test_trajectory_accuracy_seed_consistency(self, model: str) -> None:
        """Test seed consistency for TrajectoryAccuracy metric."""
        seed = 42
        num_runs = 2

        metric = metrics.TrajectoryAccuracy(
            model=model,
            seed=seed,
            track=False,
        )

        trajectory = [
            {
                "thought": "I need to search for information about France",
                "action": "search(query='France capital')",
                "observation": "Found that Paris is the capital of France",
            },
            {
                "thought": "Now I can provide the answer",
                "action": "respond(answer='Paris')",
                "observation": "Successfully provided the answer",
            },
        ]

        results = []
        for _ in range(num_runs):
            result = metric.score(
                goal="Find the capital of France",
                trajectory=trajectory,
                final_result="Paris is the capital of France",
            )
            results.append(result)
            time.sleep(0.1)

        # TrajectoryAccuracy results should be consistent with same seed
        first_result = results[0]
        for result in results[1:]:
            assert (
                result.value == first_result.value
            ), "TrajectoryAccuracy with same seed should produce identical values"
            assert (
                result.reason == first_result.reason
            ), "TrajectoryAccuracy with same seed should produce identical reasons"

    def test_seed_parameter_with_langchain_model(
        self, test_inputs: Dict[str, Any]
    ) -> None:
        """Test seed parameter behavior with LangchainChatModel."""
        seed = 42
        num_runs = 2

        # Use LangchainChatModel instead of string model
        langchain_model = langchain_chat_model.LangchainChatModel(
            chat_model=langchain_openai.ChatOpenAI(model="gpt-4o")
        )

        metric = metrics.AnswerRelevance(model=langchain_model, seed=seed, track=False)

        results = []
        for _ in range(num_runs):
            result = metric.score(
                input=test_inputs["input"],
                output=test_inputs["output"],
                context=test_inputs["context"],
            )
            results.append(result)
            time.sleep(0.1)

        # Results should be consistent with LangchainChatModel too
        first_result = results[0]
        for result in results[1:]:
            assert (
                result.value == first_result.value
            ), "LangchainChatModel with same seed should produce identical values"
            assert (
                result.reason == first_result.reason
            ), "LangchainChatModel with same seed should produce identical reasons"

    def test_seed_parameter_performance_impact(
        self, test_inputs: Dict[str, Any], model: str
    ) -> None:
        """Test that seed parameter doesn't significantly impact performance."""
        import time

        # Test without seed
        start_time = time.time()
        metric_no_seed = metrics.AnswerRelevance(model=model, track=False)
        result_no_seed = metric_no_seed.score(
            input=test_inputs["input"],
            output=test_inputs["output"],
            context=test_inputs["context"],
        )
        time_no_seed = time.time() - start_time

        # Test with seed
        start_time = time.time()
        metric_with_seed = metrics.AnswerRelevance(model=model, seed=42, track=False)
        result_with_seed = metric_with_seed.score(
            input=test_inputs["input"],
            output=test_inputs["output"],
            context=test_inputs["context"],
        )
        time_with_seed = time.time() - start_time

        # Both should complete successfully
        assert_helpers.assert_score_result(result_no_seed)
        assert_helpers.assert_score_result(result_with_seed)

        # Performance should be similar (within reasonable bounds)
        # Allow for some variance due to network/API variability
        time_ratio = time_with_seed / time_no_seed if time_no_seed > 0 else 1
        assert (
            0.5 <= time_ratio <= 2.0
        ), f"Seed parameter should not significantly impact performance. Ratio: {time_ratio}"

    def test_seed_parameter_edge_cases(
        self, test_inputs: Dict[str, Any], model: str
    ) -> None:
        """Test edge cases for seed parameter."""
        # Test with seed=0
        metric_zero = metrics.AnswerRelevance(model=model, seed=0, track=False)
        result_zero = metric_zero.score(
            input=test_inputs["input"],
            output=test_inputs["output"],
            context=test_inputs["context"],
        )
        assert_helpers.assert_score_result(result_zero)

        # Test with large seed
        metric_large = metrics.AnswerRelevance(model=model, seed=999999, track=False)
        result_large = metric_large.score(
            input=test_inputs["input"],
            output=test_inputs["output"],
            context=test_inputs["context"],
        )
        assert_helpers.assert_score_result(result_large)

        # Test with negative seed (should work but might not be deterministic)
        metric_negative = metrics.AnswerRelevance(model=model, seed=-42, track=False)
        result_negative = metric_negative.score(
            input=test_inputs["input"],
            output=test_inputs["output"],
            context=test_inputs["context"],
        )
        assert_helpers.assert_score_result(result_negative)
