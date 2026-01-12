"""Unit tests for HierarchicalRootCauseAnalyzer async wiring."""

from __future__ import annotations

import asyncio
import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    BatchAnalysis,
    FailureMode,
    HierarchicalRootCauseAnalysis,
    RootCauseAnalysis,
)
from opik_optimizer.utils.prompt_library import PromptLibrary


@pytest.fixture
def mock_prompts() -> PromptLibrary:
    """Create mock prompt library for tests."""
    defaults = {
        "batch_analysis_prompt": "Analyze batch: {formatted_batch}",
        "synthesis_prompt": "Synthesize: {batch_summaries}",
        "improve_prompt_template": "Improve: {prompts_section}",
    }
    return PromptLibrary(defaults)


@pytest.fixture
def analyzer(mock_prompts: PromptLibrary) -> HierarchicalRootCauseAnalyzer:
    """Create analyzer instance for tests."""
    return HierarchicalRootCauseAnalyzer(
        reasoning_model="gpt-4o",
        seed=42,
        max_parallel_batches=2,
        batch_size=5,
        model_parameters={"temperature": 0.1},
        prompts=mock_prompts,
        verbose=0,
    )


@pytest.fixture
def mock_evaluation_result() -> MagicMock:
    """Create a mock evaluation result with test cases."""
    mock = MagicMock()

    # Create test results with scores and reasons
    test_results = []
    for i in range(10):
        test_result = MagicMock()
        test_result.trial_id = f"trial_{i}"
        test_result.test_case = MagicMock()
        test_result.test_case.dataset_item_id = f"item_{i}"

        # Add score with reason
        score = MagicMock()
        score.name = "accuracy"
        score.value = 0.5 if i % 2 == 0 else 0.9
        score.reason = f"Test case {i} failed due to parsing error" if i % 2 == 0 else "Correct"
        score.scoring_failed = False
        test_result.score_results = [score]

        test_results.append(test_result)

    mock.test_results = test_results
    return mock


class TestHierarchicalRootCauseAnalyzer:
    """Test HierarchicalRootCauseAnalyzer async wiring."""

    def test_analyzer_initialization(self, analyzer: HierarchicalRootCauseAnalyzer) -> None:
        """Test analyzer initializes with correct parameters."""
        assert analyzer.reasoning_model == "gpt-4o"
        assert analyzer.seed == 42
        assert analyzer.max_parallel_batches == 2
        assert analyzer.batch_size == 5
        assert analyzer.verbose == 0
        assert analyzer.model_parameters == {"temperature": 0.1}

    def test_prompts_reference_shared(self, mock_prompts: PromptLibrary) -> None:
        """Test that analyzer uses shared prompts reference."""
        analyzer = HierarchicalRootCauseAnalyzer(
            reasoning_model="gpt-4o",
            seed=42,
            max_parallel_batches=2,
            batch_size=5,
            model_parameters=None,
            prompts=mock_prompts,
        )
        assert analyzer.prompts is mock_prompts

    def test_format_test_results_batch(
        self, analyzer: HierarchicalRootCauseAnalyzer, mock_evaluation_result: MagicMock
    ) -> None:
        """Test formatting of test results for batch analysis."""
        formatted = analyzer._format_test_results_batch(
            mock_evaluation_result.test_results,
            batch_start=0,
            batch_end=3,
        )

        assert "Test Case #1" in formatted
        assert "Test Case #2" in formatted
        assert "Test Case #3" in formatted
        assert "item_0" in formatted
        assert "accuracy:" in formatted

    def test_validate_reasons_present_raises_error(
        self, analyzer: HierarchicalRootCauseAnalyzer
    ) -> None:
        """Test that missing reasons raises ValueError."""
        mock_results = []
        for i in range(3):
            test_result = MagicMock()
            score = MagicMock()
            score.reason = None  # No reason
            test_result.score_results = [score]
            mock_results.append(test_result)

        with pytest.raises(ValueError, match="must include 'reason' fields"):
            analyzer._validate_reasons_present(mock_results)

    def test_validate_reasons_present_passes_with_reasons(
        self, analyzer: HierarchicalRootCauseAnalyzer, mock_evaluation_result: MagicMock
    ) -> None:
        """Test that validation passes when reasons exist."""
        # Should not raise
        analyzer._validate_reasons_present(mock_evaluation_result.test_results)


class TestHierarchicalAnalyzerAsync:
    """Test async methods of HierarchicalRootCauseAnalyzer."""

    @pytest.mark.asyncio
    async def test_analyze_batch_async_calls_model(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """Test that _analyze_batch_async calls call_model_async with correct params."""
        mock_response = RootCauseAnalysis(
            failure_modes=[
                FailureMode(
                    name="ParseError",
                    description="Failed to parse output",
                    root_cause="Malformed JSON",
                )
            ]
        )

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer._llm_calls.call_model_async",
            new_callable=AsyncMock,
        ) as mock_call:
            mock_call.return_value = mock_response

            result = await analyzer._analyze_batch_async(
                evaluation_result=mock_evaluation_result,
                batch_number=1,
                batch_start=0,
                batch_end=5,
                project_name="test-project",
            )

            # Verify the async call was made
            mock_call.assert_called_once()
            call_kwargs = mock_call.call_args.kwargs

            assert call_kwargs["model"] == "gpt-4o"
            assert call_kwargs["seed"] == 42
            assert call_kwargs["project_name"] == "test-project"
            assert call_kwargs["response_model"] is RootCauseAnalysis
            assert call_kwargs["model_parameters"] == {"temperature": 0.1}

            # Verify result
            assert isinstance(result, BatchAnalysis)
            assert result.batch_number == 1
            assert len(result.failure_modes) == 1
            assert result.failure_modes[0].name == "ParseError"

    @pytest.mark.asyncio
    async def test_synthesize_batch_analyses_async(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """Test synthesis of batch analyses."""
        batch_analyses = [
            BatchAnalysis(
                batch_number=1,
                start_index=0,
                end_index=5,
                failure_modes=[
                    FailureMode(name="Error1", description="Desc1", root_cause="Cause1")
                ],
            ),
            BatchAnalysis(
                batch_number=2,
                start_index=5,
                end_index=10,
                failure_modes=[
                    FailureMode(name="Error2", description="Desc2", root_cause="Cause2")
                ],
            ),
        ]

        mock_response = HierarchicalRootCauseAnalysis(
            total_test_cases=10,
            num_batches=2,
            unified_failure_modes=[
                FailureMode(name="UnifiedError", description="Combined", root_cause="Root")
            ],
            synthesis_notes="Merged similar errors",
        )

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer._llm_calls.call_model_async",
            new_callable=AsyncMock,
        ) as mock_call:
            mock_call.return_value = mock_response

            result = await analyzer._synthesize_batch_analyses_async(
                evaluation_result=mock_evaluation_result,
                batch_analyses=batch_analyses,
                project_name="test-project",
            )

            mock_call.assert_called_once()
            call_kwargs = mock_call.call_args.kwargs

            assert call_kwargs["response_model"] is HierarchicalRootCauseAnalysis
            assert isinstance(result, HierarchicalRootCauseAnalysis)
            assert len(result.unified_failure_modes) == 1

    @pytest.mark.asyncio
    async def test_analyze_async_full_flow(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """Test full async analysis flow with batches and synthesis."""
        batch_response = RootCauseAnalysis(
            failure_modes=[
                FailureMode(name="BatchError", description="Test", root_cause="Test")
            ]
        )

        synthesis_response = HierarchicalRootCauseAnalysis(
            total_test_cases=10,
            num_batches=2,
            unified_failure_modes=[
                FailureMode(name="UnifiedError", description="Combined", root_cause="Root")
            ],
            synthesis_notes="Analysis complete",
        )

        call_count = 0

        async def mock_call(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if kwargs.get("response_model") is RootCauseAnalysis:
                return batch_response
            return synthesis_response

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer._llm_calls.call_model_async",
            new_callable=AsyncMock,
            side_effect=mock_call,
        ):
            result = await analyzer.analyze_async(
                mock_evaluation_result,
                project_name="test-project",
            )

            # With batch_size=5 and 10 results, we should have 2 batches + 1 synthesis
            assert call_count == 3  # 2 batch analyses + 1 synthesis
            assert isinstance(result, HierarchicalRootCauseAnalysis)

    def test_analyze_sync_wrapper(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """Test synchronous analyze() wrapper calls asyncio.run."""
        synthesis_response = HierarchicalRootCauseAnalysis(
            total_test_cases=10,
            num_batches=2,
            unified_failure_modes=[],
            synthesis_notes="Test",
        )

        with patch.object(
            analyzer, "analyze_async", new_callable=AsyncMock
        ) as mock_async:
            mock_async.return_value = synthesis_response

            result = analyzer.analyze(mock_evaluation_result, project_name="test")

            mock_async.assert_called_once_with(
                mock_evaluation_result, project_name="test"
            )
            assert result is synthesis_response


class TestHierarchicalAnalyzerConcurrency:
    """Test concurrency controls in HierarchicalRootCauseAnalyzer."""

    @pytest.mark.asyncio
    async def test_semaphore_limits_concurrent_batches(
        self, mock_prompts: PromptLibrary, mock_evaluation_result: MagicMock
    ) -> None:
        """Test that semaphore limits concurrent batch processing."""
        # Create analyzer with max_parallel_batches=1
        analyzer = HierarchicalRootCauseAnalyzer(
            reasoning_model="gpt-4o",
            seed=42,
            max_parallel_batches=1,  # Only 1 at a time
            batch_size=2,  # 5 batches for 10 results
            model_parameters=None,
            prompts=mock_prompts,
            verbose=0,
        )

        concurrent_count = 0
        max_concurrent = 0

        async def track_concurrent(*args, **kwargs):
            nonlocal concurrent_count, max_concurrent
            concurrent_count += 1
            max_concurrent = max(max_concurrent, concurrent_count)
            await asyncio.sleep(0.01)  # Simulate work
            concurrent_count -= 1

            if kwargs.get("response_model") is RootCauseAnalysis:
                return RootCauseAnalysis(failure_modes=[])
            return HierarchicalRootCauseAnalysis(
                total_test_cases=10,
                num_batches=5,
                unified_failure_modes=[],
                synthesis_notes="Test",
            )

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer._llm_calls.call_model_async",
            new_callable=AsyncMock,
            side_effect=track_concurrent,
        ):
            await analyzer.analyze_async(mock_evaluation_result)

            # max_concurrent should be 1 due to semaphore
            assert max_concurrent == 1


class TestHierarchicalAnalyzerErrorHandling:
    """Test error handling in async analysis."""

    @pytest.mark.asyncio
    async def test_batch_error_propagates(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """Test that errors in batch analysis propagate correctly."""
        async def failing_call(*args, **kwargs):
            raise RuntimeError("LLM call failed")

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer._llm_calls.call_model_async",
            new_callable=AsyncMock,
            side_effect=failing_call,
        ):
            with pytest.raises(RuntimeError, match="LLM call failed"):
                await analyzer.analyze_async(mock_evaluation_result)

    @pytest.mark.asyncio
    async def test_empty_test_results_handled(
        self, analyzer: HierarchicalRootCauseAnalyzer
    ) -> None:
        """Test handling of empty test results."""
        mock_result = MagicMock()
        mock_result.test_results = []

        # Empty results should not fail validation
        # but should produce empty analysis
        synthesis_response = HierarchicalRootCauseAnalysis(
            total_test_cases=0,
            num_batches=0,
            unified_failure_modes=[],
            synthesis_notes="No data",
        )

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_root_cause_analyzer._llm_calls.call_model_async",
            new_callable=AsyncMock,
            return_value=synthesis_response,
        ):
            # With no test results, we skip batch analysis
            result = await analyzer.analyze_async(mock_result)
            # Should handle empty case gracefully
            assert isinstance(result, HierarchicalRootCauseAnalysis)
