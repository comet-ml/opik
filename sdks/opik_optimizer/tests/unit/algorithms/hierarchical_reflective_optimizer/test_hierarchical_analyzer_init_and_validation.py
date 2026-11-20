"""Unit tests for HierarchicalRootCauseAnalyzer initialization and validation helpers."""

from __future__ import annotations

from unittest.mock import MagicMock


from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from tests.unit.algorithms.hierarchical_reflective_optimizer._analyzer_test_fixtures import (
    analyzer as analyzer_fixture,
    mock_evaluation_result as mock_evaluation_result_fixture,
    mock_prompts as mock_prompts_fixture,
)

analyzer = analyzer_fixture
mock_prompts = mock_prompts_fixture
mock_evaluation_result = mock_evaluation_result_fixture


class TestHierarchicalRootCauseAnalyzer:
    """Test HierarchicalRootCauseAnalyzer wiring."""

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
