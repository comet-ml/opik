# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

import contextlib
import copy
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt, ParameterOptimizer
from opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops import (
    build_optuna_objective,
)
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType
from tests.unit.test_helpers import make_mock_dataset


class TestOptunaHistoryRedaction:
    def _run_objective(
        self,
        *,
        monkeypatch: pytest.MonkeyPatch,
    ) -> dict[str, Any]:
        dataset = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="train",
            dataset_id="ds-1",
        )
        prompt = ChatPrompt(name="p", system="Test")
        optimizer = ParameterOptimizer(verbose=0)

        parameter_space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="api_key",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                )
            ]
        )
        context = optimizer._setup_optimization(
            prompt=prompt,
            dataset=dataset,
            metric=lambda item, output: 0.5,
            project_name="Optimization",
            max_trials=1,
        )
        base_prompts = context.prompts
        expanded_parameter_space = parameter_space.expand_for_prompts(
            list(base_prompts)
        )
        evaluation_dataset = context.evaluation_dataset
        experiment_config = context.experiment_config

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        @contextlib.contextmanager
        def fake_trial_reporter(**kwargs):
            _ = kwargs
            reporter = MagicMock()
            yield reporter

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.reporting.display_trial_evaluation",
            fake_trial_reporter,
        )

        captured: dict[str, Any] = {}

        def fake_record_and_post_trial(**kwargs: Any) -> None:
            captured.update(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.runtime.record_and_post_trial",
            fake_record_and_post_trial,
        )
        monkeypatch.setattr(optimizer, "post_round", lambda *args, **kwargs: None)

        current_space_ref = {"space": expanded_parameter_space}
        stage_ref: dict[str, Any] = {"name": "global"}
        stage_counts: dict[str, int] = {}
        best_state = {"score": 0.0, "prompts": copy.deepcopy(base_prompts)}

        objective = build_optuna_objective(
            optimizer=optimizer,
            context=context,
            current_space_ref=current_space_ref,
            stage_ref=stage_ref,
            stage_counts=stage_counts,
            best_state=best_state,
            base_prompts=base_prompts,
            evaluation_dataset=evaluation_dataset,
            metric=lambda item, output: 0.5,
            agent=context.agent,
            experiment_config=experiment_config,
            n_samples=None,
            n_samples_strategy=None,
            total_trials=1,
        )

        class FakeTrial:
            def __init__(self) -> None:
                self.study = MagicMock()
                self.number = 0
                self.user_attrs: dict[str, Any] = {}

            def set_user_attr(self, key: str, value: Any) -> None:
                self.user_attrs[key] = value

            def suggest_float(
                self, name: str, low: float, high: float, step=None, log=False
            ) -> float:
                _ = (name, step, log)
                return (low + high) / 2

        objective(FakeTrial())
        return captured

    def test_redacts_sensitive_history_by_default(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        captured = self._run_objective(monkeypatch=monkeypatch)
        prompt_payload = captured["prompt_or_payload"]
        assert isinstance(prompt_payload, dict)
        assert isinstance(prompt_payload["p"], ChatPrompt)
        params = captured["extra"]["parameters"]
        assert params.get("p.api_key") == "<REDACTED>"
