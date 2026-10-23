"""Unit tests for HierarchicalRootCauseAnalyzer initialization and validation helpers."""

from __future__ import annotations

import datetime
from typing import Any
from unittest.mock import MagicMock

import pytest
from opik.message_processing.emulation import models

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

    def test_format_test_results_batch_includes_agent_trace(
        self, analyzer: HierarchicalRootCauseAnalyzer, mock_evaluation_result: MagicMock
    ) -> None:
        now = datetime.datetime.now()

        def span(**kw: Any) -> models.SpanModel:
            return models.SpanModel(start_time=now, source="sdk", **kw)

        trace = models.TraceModel(
            id="trace-0",
            start_time=now,
            name="evaluation_task",
            project_name="p",
            source="sdk",
            spans=[
                span(
                    id="task",
                    name="evaluation_task",
                    input={"question": "dup"},
                    spans=[
                        span(
                            id="llm",
                            name="completion",
                            type="llm",
                            model="gpt-4o",
                            input={"messages": "SYSTEM PROMPT"},
                            output={
                                "choices": [
                                    {
                                        "message": {
                                            "content": None,
                                            "tool_calls": [
                                                {
                                                    "function": {
                                                        "name": "search",
                                                        "arguments": '{"query": "x"}',
                                                    }
                                                }
                                            ],
                                        }
                                    }
                                ]
                            },
                        ),
                        span(
                            id="tool",
                            name="search",
                            type="tool",
                            input={"query": "capital of france"},
                            error_info={"message": "timeout"},
                        ),
                        span(
                            id="llm-2",
                            name="completion",
                            type="llm",
                            input={"messages": "REPLAYED HISTORY"},
                        ),
                    ],
                ),
                span(
                    id="scoring",
                    name="metrics_calculation",
                    spans=[span(id="metric", name="accuracy_arg_validation")],
                ),
            ],
        )
        results = mock_evaluation_result.test_results
        results[0].test_case.trace_id = "trace-0"

        formatted = analyzer._format_test_results_batch(
            results, 0, 2, traces={"trace-0": trace}
        )
        case_0, case_1 = formatted.split("Test Case #2")

        assert "- [llm] completion (gpt-4o)" in case_0
        assert 'search({\\"query\\": \\"x\\"})' in case_0
        assert "choices" not in case_0
        assert "arg_validation" not in case_0
        assert "SYSTEM PROMPT" in case_0
        assert "REPLAYED HISTORY" not in case_0
        assert '  input: {"query": "capital of france"}' in case_0
        assert "timeout" in case_0
        assert "Agent trace" not in case_1

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
