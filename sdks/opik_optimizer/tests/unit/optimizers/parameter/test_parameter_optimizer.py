import types

import pytest

import opik_optimizer
from opik_optimizer.algorithms.parameter_optimizer.parameter_search_space import (
    ParameterSearchSpace,
)


def test_parameter_optimizer_selects_best_parameters(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    optimizer = opik_optimizer.ParameterOptimizer(
        model="gpt-4o-mini",
        default_n_trials=2,
        seed=42,
        local_search_ratio=0.5,
        local_search_scale=0.5,
    )

    prompt = opik_optimizer.ChatPrompt(system="Hi there")
    prompt.model = "gpt-4o-mini"
    prompt.model_kwargs = {"temperature": 0.1}

    parameter_space = ParameterSearchSpace.model_validate(
        {
            "temperature": {"type": "float", "min": 0.0, "max": 1.0},
        }
    )

    samples = [{"temperature": 0.2}, {"temperature": 0.9}]

    def fake_suggest(self: ParameterSearchSpace, trial: object) -> dict[str, float]:
        index = getattr(trial, "number", 0)
        return samples[min(index, len(samples) - 1)]

    def fake_evaluate(
        self: opik_optimizer.ParameterOptimizer,
        prompt: opik_optimizer.ChatPrompt,
        dataset: object,
        metric: object,
        *,
        n_threads: int,
        verbose: int,
        experiment_config: dict | None,
        n_samples: int | None,
        agent_class: type | None,
    ) -> float:
        return float(prompt.model_kwargs.get("temperature", 0.0))

    monkeypatch.setattr(ParameterSearchSpace, "suggest", fake_suggest, raising=False)
    monkeypatch.setattr(
        opik_optimizer.ParameterOptimizer,
        "evaluate_prompt",
        fake_evaluate,
        raising=False,
    )
    monkeypatch.setattr(
        opik_optimizer.ParameterOptimizer,
        "_validate_optimization_inputs",
        lambda *args, **kwargs: None,
        raising=False,
    )
    monkeypatch.setattr(
        opik_optimizer.ParameterOptimizer,
        "_setup_agent_class",
        lambda self, prompt, agent_class=None: types.SimpleNamespace(),
        raising=False,
    )
    monkeypatch.setattr(
        "opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer.optuna_importance.get_param_importances",
        lambda *args, **kwargs: {"temperature": 1.0},
        raising=False,
    )

    # Mock the opik client's create_optimization method
    mock_optimization = types.SimpleNamespace(id="opt-123")
    monkeypatch.setattr(
        "opik.Opik.create_optimization",
        lambda self, **kwargs: mock_optimization,
        raising=False,
    )

    # Create mock dataset with required attributes
    mock_dataset = types.SimpleNamespace(name="test-dataset", id="dataset-123")

    result = optimizer.optimize_parameter(
        prompt=prompt,
        dataset=mock_dataset,
        metric=lambda *_: 0.0,
        parameter_space=parameter_space,
        max_trials=2,
    )

    assert pytest.approx(result.initial_score) == 0.1
    assert pytest.approx(result.score) == 0.9
    assert result.details["optimized_parameters"]["temperature"] == 0.9
    assert len(result.history) == 3
    assert len(result.details["rounds"]) == 2
    assert result.details["model"] == "gpt-4o-mini"
    assert result.details["temperature"] == 0.9
    assert result.details["parameter_importance"]["temperature"] == 1.0
    assert {round_entry["stage"] for round_entry in result.details["rounds"]} == {
        "global",
        "local",
    }
    assert "global" in result.details["search_ranges"]
    assert "temperature" in result.details["search_ranges"]["global"]
