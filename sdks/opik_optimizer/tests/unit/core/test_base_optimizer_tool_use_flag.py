"""Unit tests for BaseOptimizer allow_tool_use plumbing."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from tests.unit.fixtures.base_optimizer_test_helpers import (
    ConcreteOptimizer,
    _ToolFlagAgent,
)
from tests.unit.test_helpers import make_fake_evaluator, make_mock_dataset


class TestToolUseFlag:
    def test_evaluate_prompt_passes_allow_tool_use(
        self, monkeypatch: pytest.MonkeyPatch
    ):
        optimizer = ConcreteOptimizer(model="gpt-4")
        agent = _ToolFlagAgent()
        dataset = make_mock_dataset()

        def assert_output(output: dict[str, Any]) -> None:
            assert agent.last_allow_tool_use is True

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate",
            make_fake_evaluator(assert_output=assert_output),
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

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate",
            make_fake_evaluator(assert_output=assert_output),
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

    def test_setup_optimization_allows_none_optimize_prompts(
        self, mock_opik_client
    ) -> None:
        mock_opik_client()
        optimizer = ConcreteOptimizer(model="gpt-4")
        dataset = make_mock_dataset()
        prompt = ChatPrompt(system="Test", user="Query")
        context = optimizer._setup_optimization(
            prompt=prompt,
            dataset=dataset,
            metric=lambda *_: 1.0,
            optimize_prompts=None,
            optimize_tools=None,
        )
        assert context.extra_params.get("optimizable_roles") == {"system"}
