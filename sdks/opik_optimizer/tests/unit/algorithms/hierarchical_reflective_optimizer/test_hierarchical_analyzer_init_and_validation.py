"""Unit tests for HierarchicalRootCauseAnalyzer initialization and validation helpers."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.utils.prompt_library import PromptLibrary
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

    def test_analyzer_initialization(
        self, analyzer: HierarchicalRootCauseAnalyzer
    ) -> None:
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
        for _i in range(3):
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
        analyzer._validate_reasons_present(mock_evaluation_result.test_results)

