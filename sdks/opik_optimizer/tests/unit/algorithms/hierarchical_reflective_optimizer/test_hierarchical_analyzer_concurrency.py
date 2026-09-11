"""Unit tests for HierarchicalRootCauseAnalyzer concurrency controls."""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    HierarchicalRootCauseAnalysis,
    RootCauseAnalysis,
)
from opik_optimizer.utils.prompt_library import PromptLibrary
from tests.unit.algorithms.hierarchical_reflective_optimizer._analyzer_test_fixtures import (
    mock_evaluation_result as mock_evaluation_result_fixture,
    mock_prompts as mock_prompts_fixture,
)

mock_prompts = mock_prompts_fixture
mock_evaluation_result = mock_evaluation_result_fixture


class TestHierarchicalAnalyzerConcurrency:
    """Test concurrency controls in HierarchicalRootCauseAnalyzer."""

    @pytest.mark.asyncio
    async def test_semaphore_limits_concurrent_batches(
        self, mock_prompts: PromptLibrary, mock_evaluation_result: MagicMock
    ) -> None:
        """Test that semaphore limits concurrent batch processing."""
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

        async def track_concurrent(*args: Any, **kwargs: Any) -> Any:
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
            "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async",
            new_callable=AsyncMock,
            side_effect=track_concurrent,
        ):
            await analyzer.analyze_async(mock_evaluation_result)

            assert max_concurrent == 1
