# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

import contextlib
import copy
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any, no_type_check
from unittest.mock import MagicMock

import pytest
from optuna.trial import TrialState

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


class TestReporterLifecycle:
    def test_sets_and_clears_reporter_during_trial(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        dataset = make_mock_dataset(
            [{"id": "1", "question": "Q1", "answer": "A1"}],
            name="train",
            dataset_id="ds-1",
        )
        prompt = ChatPrompt(name="p", system="Test")

        optimizer = ParameterOptimizer(model="gpt-4o", verbose=0)
        events: list[str] = []

        orig_set = optimizer._set_reporter
        orig_clear = optimizer._clear_reporter

        def tracking_set(reporter: Any) -> None:
            events.append("set")
            orig_set(reporter)

        def tracking_clear() -> None:
            events.append("clear")
            orig_clear()

        monkeypatch.setattr(optimizer, "_set_reporter", tracking_set)
        monkeypatch.setattr(optimizer, "_clear_reporter", tracking_clear)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        class FakeTrial:
            def __init__(self, number: int) -> None:
                self.number = number
                self.value: float | None = None
                self.user_attrs: dict[str, Any] = {}
                self.datetime_start = datetime.now(timezone.utc)
                self.datetime_complete = self.datetime_start
                self.state = TrialState.COMPLETE
                self.pruner = None

            def set_user_attr(self, key: str, value: Any) -> None:
                self.user_attrs[key] = value

            def suggest_float(
                self, name: str, low: float, high: float, step=None, log=False
            ) -> float:
                _ = (name, step, log)
                return (low + high) / 2

        @no_type_check
        class FakeStudy:
            def __init__(self) -> None:
                self.trials: list[FakeTrial] = []
                self.pruner = MagicMock()

            def optimize(
                self,
                objective: Callable[[FakeTrial], float],
                n_trials: int,
                timeout: float | None = None,
                callbacks: list | None = None,
                show_progress_bar: bool = False,
            ) -> None:
                _ = (n_trials, timeout, callbacks, show_progress_bar)
                trial = FakeTrial(0)
                trial.value = objective(trial)
                self.trials.append(trial)

        def make_study(direction: str, sampler: Any) -> FakeStudy:
            _ = (direction, sampler)
            return FakeStudy()

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer.optuna.create_study",
            make_study,
        )

        @contextlib.contextmanager
        def fake_trial_reporter(**kwargs):
            _ = kwargs
            reporter = MagicMock()
            yield reporter

        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.reporting.display_trial_evaluation",
            fake_trial_reporter,
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.optuna_importance.get_param_importances",
            lambda study, params=None, target=None: {},
        )
        monkeypatch.setattr(
            "opik_optimizer.algorithms.parameter_optimizer.ops.optuna_ops.sensitivity_analysis",
            lambda trials, specs: {},
        )

        space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                )
            ]
        )

        optimizer.optimize_parameter(
            prompt=prompt,
            dataset=dataset,
            metric=lambda item, output: 0.5,
            parameter_space=space,
            max_trials=1,
        )

        assert events == ["set", "clear"]
        assert optimizer._reporter is None


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
        optimizer = ParameterOptimizer(model="gpt-4o", verbose=0)

        (
            context,
            base_prompts,
            base_model_kwargs,
            expanded_parameter_space,
            _,
            evaluation_dataset,
            experiment_config,
        ) = optimizer._prepare_parameter_context(
            prompt=prompt,
            dataset=dataset,
            metric=lambda item, output: 0.5,
            parameter_space=ParameterSearchSpace(
                parameters=[
                    ParameterSpec(
                        name="api_key",
                        distribution=ParameterType.FLOAT,
                        low=0.0,
                        high=1.0,
                    )
                ]
            ),
            validation_dataset=None,
            experiment_config=None,
            n_samples=None,
            agent=None,
            project_name="Optimization",
            optimization_id=None,
            max_trials=1,
        )

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
            base_model_kwargs=base_model_kwargs,
            evaluation_dataset=evaluation_dataset,
            metric=lambda item, output: 0.5,
            agent=context.agent,
            experiment_config=experiment_config,
            n_samples=None,
            total_trials=1,
        )

        class FakeTrial:
            def __init__(self) -> None:
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
