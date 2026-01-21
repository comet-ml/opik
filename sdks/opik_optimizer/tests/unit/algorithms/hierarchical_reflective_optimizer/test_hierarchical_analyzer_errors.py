"""Unit tests for HierarchicalRootCauseAnalyzer error handling."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    HierarchicalRootCauseAnalysis,
)
from tests.unit.algorithms.hierarchical_reflective_optimizer._analyzer_test_fixtures import (
    analyzer as analyzer_fixture,
    mock_evaluation_result as mock_evaluation_result_fixture,
)

analyzer = analyzer_fixture
mock_evaluation_result = mock_evaluation_result_fixture


class TestHierarchicalAnalyzerErrorHandling:
    """Test error handling in async analysis."""

    @pytest.mark.asyncio
    async def test_batch_error_propagates(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """Test that errors in batch analysis propagate correctly."""

        async def failing_call(*args: Any, **kwargs: Any) -> Any:
            _ = (args, kwargs)
            raise RuntimeError("LLM call failed")

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async",
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

        synthesis_response = HierarchicalRootCauseAnalysis(
            total_test_cases=0,
            num_batches=0,
            unified_failure_modes=[],
            synthesis_notes="No data",
        )

        with patch(
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async",
            new_callable=AsyncMock,
            return_value=synthesis_response,
        ):
            result = await analyzer.analyze_async(mock_result)
            assert isinstance(result, HierarchicalRootCauseAnalysis)

