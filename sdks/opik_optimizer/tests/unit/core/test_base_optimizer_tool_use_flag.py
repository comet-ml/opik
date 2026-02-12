"""Unit tests for BaseOptimizer allow_tool_use plumbing."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik.evaluation import evaluation_result as opik_evaluation_result
from opik.evaluation import test_case as opik_test_case
from opik.evaluation import test_result as opik_test_result
from opik.evaluation.metrics import score_result
from tests.unit.fixtures.base_optimizer_test_helpers import (
    ConcreteOptimizer,
    _ToolFlagAgent,
)
from tests.unit.test_helpers import make_mock_dataset


class TestToolUseFlag:
    def test_evaluate_prompt_passes_allow_tool_use(
        self, monkeypatch: pytest.MonkeyPatch
    ):
        optimizer = ConcreteOptimizer(model="gpt-4")
        agent = _ToolFlagAgent()
        dataset = make_mock_dataset()

        def assert_output(output: dict[str, Any]) -> None:
            assert agent.last_allow_tool_use is True

        def fake_evaluate_with_result(
            dataset: Any,
            evaluated_task: Any,
            metric: Any,
            num_threads: int,
            **kwargs: Any,
        ) -> tuple[float, opik_evaluation_result.EvaluationResultOnDictItems]:
            _ = dataset, metric, num_threads, kwargs
            output = evaluated_task({"id": "1", "input": "x"})
            assert_output(output)
            result = opik_test_result.TestResult(
                test_case=opik_test_case.TestCase(
                    trace_id="trace-1",
                    dataset_item_id="item-1",
                    task_output=output,
                    dataset_item_content={"id": "1", "input": "x"},
                ),
                score_results=[score_result.ScoreResult(name="<lambda>", value=1.0)],
                trial_id=0,
            )
            return (
                1.0,
                opik_evaluation_result.EvaluationResultOnDictItems(
                    test_results=[result]
                ),
            )

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate_with_result",
            fake_evaluate_with_result,
        )

        prompt = ChatPrompt(system="Test", user="Query")
        optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            agent=agent,
            allow_tool_use=True,
        )

    def test_evaluate_prompt_defaults_tool_use(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        optimizer = ConcreteOptimizer(model="gpt-4")
        agent = _ToolFlagAgent()
        dataset = make_mock_dataset()

        def assert_output(output: dict[str, Any]) -> None:
            assert agent.last_allow_tool_use is True

        def fake_evaluate_with_result(
            dataset: Any,
            evaluated_task: Any,
            metric: Any,
            num_threads: int,
            **kwargs: Any,
        ) -> tuple[float, opik_evaluation_result.EvaluationResultOnDictItems]:
            _ = dataset, metric, num_threads, kwargs
            output = evaluated_task({"id": "1", "input": "x"})
            assert_output(output)
            result = opik_test_result.TestResult(
                test_case=opik_test_case.TestCase(
                    trace_id="trace-1",
                    dataset_item_id="item-1",
                    task_output=output,
                    dataset_item_content={"id": "1", "input": "x"},
                ),
                score_results=[score_result.ScoreResult(name="<lambda>", value=1.0)],
                trial_id=0,
            )
            return (
                1.0,
                opik_evaluation_result.EvaluationResultOnDictItems(
                    test_results=[result]
                ),
            )

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate_with_result",
            fake_evaluate_with_result,
        )

        prompt = ChatPrompt(system="Test", user="Query")
        optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            agent=agent,
        )

    def test_setup_optimization_sets_allow_tool_use(self) -> None:
        optimizer = ConcreteOptimizer(model="gpt-4")
        dataset = make_mock_dataset()
        prompt = ChatPrompt(system="Test", user="Query")
        context = optimizer._setup_optimization(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            allow_tool_use=False,
        )
        assert context.allow_tool_use is False
