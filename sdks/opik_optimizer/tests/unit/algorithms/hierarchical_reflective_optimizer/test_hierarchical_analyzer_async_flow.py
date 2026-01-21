"""Unit tests for HierarchicalRootCauseAnalyzer async analysis flow."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    BatchAnalysis,
    FailureMode,
    HierarchicalRootCauseAnalysis,
    RootCauseAnalysis,
)
from tests.unit.algorithms.hierarchical_reflective_optimizer._analyzer_test_fixtures import (
    analyzer as analyzer_fixture,
    mock_evaluation_result as mock_evaluation_result_fixture,
)

analyzer = analyzer_fixture
mock_evaluation_result = mock_evaluation_result_fixture


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
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async",
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

            mock_call.assert_called_once()
            call_kwargs = mock_call.call_args.kwargs

            assert call_kwargs["model"] == "gpt-4o"
            assert call_kwargs["seed"] == 42
            assert call_kwargs["project_name"] == "test-project"
            assert call_kwargs["response_model"] is RootCauseAnalysis
            assert call_kwargs["model_parameters"] == {"temperature": 0.1}

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
                FailureMode(
                    name="UnifiedError", description="Combined", root_cause="Root"
                )
            ],
            synthesis_notes="Merged similar errors",
        )

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async",
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
                FailureMode(
                    name="UnifiedError", description="Combined", root_cause="Root"
                )
            ],
            synthesis_notes="Analysis complete",
        )

        call_count = 0

        async def mock_call(*args: Any, **kwargs: Any) -> Any:
            nonlocal call_count
            call_count += 1
            if kwargs.get("response_model") is RootCauseAnalysis:
                return batch_response
            return synthesis_response

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async",
            new_callable=AsyncMock,
            side_effect=mock_call,
        ):
            result = await analyzer.analyze_async(
                mock_evaluation_result,
                project_name="test-project",
            )

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

