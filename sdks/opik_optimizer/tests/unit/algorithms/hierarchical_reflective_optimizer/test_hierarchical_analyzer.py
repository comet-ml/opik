"""Unit tests for HierarchicalRootCauseAnalyzer."""

from __future__ import annotations

import asyncio
import datetime
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from opik.message_processing.emulation import models

from opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops import (
    HierarchicalRootCauseAnalyzer,
)
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.types import (
    FailureMode,
    HierarchicalRootCauseAnalysis,
    RootCauseAnalysis,
)
from opik_optimizer.core.reasoning import build_reasoning_messages
from opik_optimizer.utils.prompt_library import PromptLibrary

_CALL_MODEL_ASYNC = "opik_optimizer.algorithms.hierarchical_reflective_optimizer.rootcause_ops._llm_calls.call_model_async"


def _make_analyzer(**overrides: Any) -> HierarchicalRootCauseAnalyzer:
    kwargs: dict[str, Any] = {
        "reasoning_model": "gpt-4o",
        "seed": 42,
        "max_parallel_batches": 2,
        "batch_size": 5,
        "model_parameters": {"temperature": 0.1},
        "prompts": PromptLibrary(
            {
                "batch_analysis_prompt": "Analyze the attached batch.",
                "synthesis_prompt": "Synthesize: {batch_summaries}",
                "improve_prompt_template": "Improve: {prompts_section}",
            }
        ),
        "verbose": 0,
    }
    kwargs.update(overrides)
    return HierarchicalRootCauseAnalyzer(**kwargs)


def _synthesis(num_batches: int = 2) -> HierarchicalRootCauseAnalysis:
    return HierarchicalRootCauseAnalysis(
        total_test_cases=10,
        num_batches=num_batches,
        unified_failure_modes=[
            FailureMode(name="UnifiedError", description="Combined", root_cause="Root")
        ],
        synthesis_notes="Analysis complete",
    )


@pytest.fixture
def analyzer() -> HierarchicalRootCauseAnalyzer:
    return _make_analyzer()


@pytest.fixture
def mock_evaluation_result() -> MagicMock:
    """Ten test results, every other one failing with a reason."""
    mock = MagicMock()
    test_results = []
    for i in range(10):
        test_result = MagicMock()
        test_result.trial_id = f"trial_{i}"
        test_result.test_case = MagicMock()
        test_result.test_case.dataset_item_id = f"item_{i}"
        score = MagicMock()
        score.name = "accuracy"
        score.value = 0.5 if i % 2 == 0 else 0.9
        score.reason = (
            f"Test case {i} failed due to parsing error" if i % 2 == 0 else "Correct"
        )
        score.scoring_failed = False
        test_result.score_results = [score]
        test_results.append(test_result)
    mock.test_results = test_results
    return mock


class TestAnalyze:
    def test_sync_analyze_batches_then_synthesizes(
        self,
        analyzer: HierarchicalRootCauseAnalyzer,
        mock_evaluation_result: MagicMock,
    ) -> None:
        """10 results at batch_size=5 -> 2 batch analyses + 1 synthesis."""
        synthesis_response = _synthesis()
        calls: list[Any] = []

        async def fake_call(*args: Any, **kwargs: Any) -> Any:
            calls.append(kwargs["response_model"])
            if kwargs["response_model"] is RootCauseAnalysis:
                return RootCauseAnalysis(
                    failure_modes=[
                        FailureMode(
                            name="BatchError", description="Test", root_cause="Test"
                        )
                    ]
                )
            return synthesis_response

        with patch(_CALL_MODEL_ASYNC, new_callable=AsyncMock, side_effect=fake_call):
            result = analyzer.analyze(
                mock_evaluation_result, project_name="test-project"
            )

        assert calls == [
            RootCauseAnalysis,
            RootCauseAnalysis,
            HierarchicalRootCauseAnalysis,
        ]
        assert result is synthesis_response

    @pytest.mark.asyncio
    async def test_semaphore_limits_concurrent_batches(
        self, mock_evaluation_result: MagicMock
    ) -> None:
        analyzer = _make_analyzer(max_parallel_batches=1, batch_size=2)
        concurrent_count = 0
        max_concurrent = 0

        async def track_concurrent(*args: Any, **kwargs: Any) -> Any:
            nonlocal concurrent_count, max_concurrent
            concurrent_count += 1
            max_concurrent = max(max_concurrent, concurrent_count)
            await asyncio.sleep(0.01)
            concurrent_count -= 1
            if kwargs.get("response_model") is RootCauseAnalysis:
                return RootCauseAnalysis(failure_modes=[])
            return _synthesis(num_batches=5)

        with patch(
            _CALL_MODEL_ASYNC, new_callable=AsyncMock, side_effect=track_concurrent
        ):
            await analyzer.analyze_async(mock_evaluation_result)

        assert max_concurrent == 1


def test_each_batch_sends_every_case_once_with_its_agent_trace(
    analyzer: HierarchicalRootCauseAnalyzer, mock_evaluation_result: MagicMock
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
    for index, result in enumerate(results):
        result.test_case.trace_id = f"trace-{index}"
        result.test_case.dataset_item_content = {"question": f"item_{index}"}
        result.test_case.task_output = {"output": f"answer {index}"}
        for score in result.score_results:
            score.model_dump.return_value = {
                "name": score.name,
                "value": score.value,
                "reason": score.reason,
            }
    batches: list[list[str]] = []

    async def fake_call(*args: Any, **kwargs: Any) -> Any:
        if kwargs["response_model"] is RootCauseAnalysis:
            rendered = build_reasoning_messages(
                kwargs["messages"], evidence=kwargs["evidence"]
            )
            batches.append([str(m["content"]) for m in rendered[1:]])
            return RootCauseAnalysis(failure_modes=[])
        return _synthesis()

    with patch(_CALL_MODEL_ASYNC, new_callable=AsyncMock, side_effect=fake_call):
        analyzer.analyze(mock_evaluation_result, traces={"trace-0": trace})

    # batch_size=5: every case of each batch is sent, once, as its own message.
    assert [len(batch) for batch in batches] == [5, 5]
    case_0, case_1 = batches[0][0], batches[0][1]
    assert "item_0" in case_0 and "accuracy" in case_0
    assert "- [llm] completion (gpt-4o)" in case_0
    assert "search(" in case_0 and "timeout" in case_0
    assert "SYSTEM PROMPT" in case_0
    assert "REPLAYED HISTORY" not in case_0
    assert "arg_validation" not in case_0
    assert "agent_trace" not in case_1
