"""Unit tests for HierarchicalRootCauseAnalyzer async analysis flow."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch


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


class TestHierarchicalAnalyzerAsync:
    """Test async methods of HierarchicalRootCauseAnalyzer."""

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
