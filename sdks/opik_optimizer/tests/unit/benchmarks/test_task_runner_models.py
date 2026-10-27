"""Benchmark evaluation settings must never come from optimizer reasoning settings."""

from collections.abc import Callable
from types import SimpleNamespace
from typing import Any

import pytest

from benchmarks.utils import task_runner
from benchmarks.packages import registry as benchmark_config
from benchmarks.core.types import TASK_STATUS_FAILED, TaskResult
from opik_optimizer import ChatPrompt
from tests.unit.fixtures import user_message


class DummyOptimizer:
    def __init__(
        self,
        model: str = "optimizer-default",
        model_parameters: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        self.model = f"{model}-from-optimizer"
        self.model_parameters = {**(model_parameters or {}), "source": "optimizer"}
        self.evaluations: list[dict[str, Any]] = []

    def optimize_prompt(self, **kwargs: Any) -> Any:
        prompt = kwargs["prompt"]
        return SimpleNamespace(
            prompt=prompt if isinstance(prompt, dict) else [user_message("hi")],
            llm_calls=0,
            history=[],
        )

    def evaluate_prompt(self, **kwargs: Any) -> float:
        self.evaluations.append(kwargs)
        return 0.0


@pytest.fixture
def run_task(monkeypatch: pytest.MonkeyPatch) -> Callable[..., Any]:
    bundle = task_runner.DatasetBundle(
        train_name="dummy",
        train=[{"id": 1}],
        validation_name=None,
        validation=None,
        test_name=None,
        test=None,
        evaluation_name="dummy",
        evaluation_role="train",
        evaluation=[{"id": 1}],
        requested_split=None,
    )
    monkeypatch.setattr(task_runner, "resolve_dataset_bundle", lambda **_: bundle)
    monkeypatch.setattr(task_runner, "resolve_package", lambda _: None)
    monkeypatch.setattr(
        benchmark_config,
        "DATASET_CONFIG",
        {
            "dummy": benchmark_config.BenchmarkDatasetConfig(
                name="dummy", display_name="Dummy", metrics=[lambda *_: 0.0]
            )
        },
    )

    def run(
        *,
        optimizer_class: type[DummyOptimizer] = DummyOptimizer,
        constructor_parameters: dict[str, Any] | None = None,
        optimizer_params_override: dict[str, Any] | None = None,
        model_name: str = "agent-model",
        model_parameters: dict[str, Any] | None = None,
        test_mode: bool = True,
    ) -> tuple[TaskResult, list[DummyOptimizer]]:
        instances = []

        def construct(**kwargs: Any) -> DummyOptimizer:
            instance = optimizer_class(**kwargs)
            instances.append(instance)
            return instance

        monkeypatch.setattr(
            benchmark_config,
            "OPTIMIZER_CONFIGS",
            {
                "dummy_opt": benchmark_config.BenchmarkOptimizerConfig(
                    class_name="DummyOptimizer", params=constructor_parameters or {}
                )
            },
        )
        fake_module = SimpleNamespace(**task_runner.opik_optimizer.__dict__)
        fake_module.DummyOptimizer = construct
        monkeypatch.setattr(task_runner, "opik_optimizer", fake_module)
        result = task_runner.execute_task(
            task_id="t1",
            dataset_name="dummy",
            optimizer_name="dummy_opt",
            model_name=model_name,
            model_parameters=model_parameters,
            test_mode=test_mode,
            optimizer_params_override=optimizer_params_override,
            optimizer_prompt_params_override=None,
            prompt_messages=[user_message("{text}")],
        )
        return result, instances

    return run


@pytest.mark.parametrize("agent_parameters", [None, {}, {"temperature": 0.7}])
@pytest.mark.parametrize(
    ("optimizer_parameters", "override"),
    [
        ({}, None),
        ({"model": "reasoning-model", "model_parameters": {}}, None),
        (
            {"model": "reasoning-model", "model_parameters": {"temperature": 0.9}},
            {"model_parameters": {}},
        ),
        ({"model": "old-model"}, {"model": "reasoning-model"}),
    ],
)
def test_runner_keeps_agent_and_optimizer_settings_independent(
    run_task: Callable[..., Any],
    agent_parameters: dict[str, Any] | None,
    optimizer_parameters: dict[str, Any],
    override: dict[str, Any] | None,
) -> None:
    result, instances = run_task(
        model_parameters=agent_parameters,
        constructor_parameters=optimizer_parameters,
        optimizer_params_override=override,
    )
    optimizer = instances[0]
    expected_optimizer = {**optimizer_parameters, **(override or {})}
    assert result.optimizer_params_used == expected_optimizer
    assert (
        optimizer.model
        == f"{expected_optimizer.get('model', 'optimizer-default')}-from-optimizer"
    )
    assert optimizer.model_parameters == {
        **(expected_optimizer.get("model_parameters") or {}),
        "source": "optimizer",
    }
    assert len(optimizer.evaluations) == 2
    for prompt in [result.initial_prompt, result.optimized_prompt] + [
        evaluation["prompt"] for evaluation in optimizer.evaluations
    ]:
        assert isinstance(prompt, ChatPrompt)
        assert prompt.model == "agent-model"
        assert prompt.model_kwargs == (
            agent_parameters if agent_parameters is not None else {}
        )


@pytest.mark.parametrize("model", ["", "   "])
@pytest.mark.parametrize("test_mode", [False, True])
def test_empty_agent_models_fail_instead_of_using_optimizer_settings(
    run_task: Callable[..., Any], model: str, test_mode: bool
) -> None:
    kwargs = dict(
        model_name=model,
        test_mode=test_mode,
        constructor_parameters={"model": "reasoning-model"},
    )
    if test_mode:
        with pytest.raises(ValueError, match="evaluation model.*empty"):
            run_task(**kwargs)
    else:
        result, instances = run_task(**kwargs)
        assert result.status == TASK_STATUS_FAILED
        assert "evaluation model" in result.error_message
        assert not instances


@pytest.mark.parametrize("field", ["model", "reasoning_model"])
@pytest.mark.parametrize("model", ["", "   "])
def test_empty_optimizer_models_fail_instead_of_using_agent_settings(
    run_task: Callable[..., Any], field: str, model: str
) -> None:
    with pytest.raises(ValueError, match="optimizer.*empty"):
        run_task(constructor_parameters={field: model})


def test_task_result_prefers_optimizer_native_history(
    run_task: Callable[..., Any],
) -> None:
    class WithHistory(DummyOptimizer):
        def optimize_prompt(self, **kwargs: Any) -> Any:
            result = super().optimize_prompt(**kwargs)
            result.llm_calls = 3
            result.history = [
                {
                    "round_index": 0,
                    "trials": [{"trial_index": 0, "score": 0.1}],
                    "best_score": 0.1,
                }
            ]
            return result

    result, _ = run_task(optimizer_class=WithHistory)
    assert result.status == "Success"
    assert result.optimization_history == {
        "rounds": [
            {
                "round_index": 0,
                "trials": [{"trial_index": 0, "score": 0.1}],
                "best_score": 0.1,
            }
        ]
    }
    assert result.llm_calls_total_optimization == 3


def test_parameter_optimizer_prompt_gets_only_evaluation_settings(
    run_task: Callable[..., Any],
) -> None:
    class NoReasoningModel(DummyOptimizer):
        def __init__(self, **kwargs: Any) -> None:
            assert kwargs == {"seed": 1}
            self.model = None  # type: ignore[assignment]
            self.model_parameters = {}
            self.evaluations = []

    result, _ = run_task(
        optimizer_class=NoReasoningModel,
        constructor_parameters={"seed": 1},
        model_parameters={"temperature": 0.7},
    )
    assert isinstance(result.initial_prompt, ChatPrompt)
    assert isinstance(result.optimized_prompt, ChatPrompt)
    assert result.initial_prompt.model == result.optimized_prompt.model == "agent-model"
    assert (
        result.initial_prompt.model_kwargs
        == result.optimized_prompt.model_kwargs
        == {"temperature": 0.7}
    )


def test_package_agents_and_prompt_bundles_keep_their_own_models(
    run_task: Callable[..., Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    prompts = {
        "planner": ChatPrompt(
            name="planner", user="{text}", model="planner-model", model_parameters={}
        ),
        "answerer": ChatPrompt(
            name="answerer",
            user="{text}",
            model="answerer-model",
            model_parameters={"temperature": 0.1},
        ),
    }
    agent = SimpleNamespace(model=None, model_parameters=None)

    def build_agent(model_name: str, model_parameters: dict[str, Any] | None) -> Any:
        agent.model = model_name
        agent.model_parameters = model_parameters
        return agent

    package = SimpleNamespace(
        build_agent=build_agent, build_initial_prompt=lambda: prompts
    )
    monkeypatch.setattr(
        task_runner,
        "resolve_package",
        lambda _: SimpleNamespace(key="dummy", package=package),
    )
    result, instances = run_task(
        model_parameters={},
        constructor_parameters={
            "model": "reasoning-model",
            "model_parameters": {"temperature": 0.9},
        },
    )
    assert agent.model == "agent-model"
    assert agent.model_parameters == {}
    assert result.initial_prompt == result.optimized_prompt == prompts
    for evaluation in instances[0].evaluations:
        assert evaluation["agent"] is agent
        assert {key: prompt.model for key, prompt in evaluation["prompt"].items()} == {
            "planner": "planner-model",
            "answerer": "answerer-model",
        }


def test_returned_prompt_keeps_explicitly_tuned_evaluation_settings(
    run_task: Callable[..., Any],
) -> None:
    class ModelSearch(DummyOptimizer):
        def optimize_prompt(self, **kwargs: Any) -> Any:
            result = super().optimize_prompt(**kwargs)
            result.prompt = kwargs["prompt"].copy()
            result.prompt.model = "tuned-agent-model"
            result.prompt.model_kwargs = {"temperature": 0.2}
            return result

    result, instances = run_task(
        optimizer_class=ModelSearch,
        model_parameters={},
        constructor_parameters={
            "model": "reasoning-model",
            "model_parameters": {"temperature": 0.9},
        },
    )
    assert isinstance(result.initial_prompt, ChatPrompt)
    assert result.initial_prompt.model == "agent-model"
    assert result.initial_prompt.model_kwargs == {}
    final_prompt = instances[0].evaluations[-1]["prompt"]
    assert final_prompt is result.optimized_prompt
    assert final_prompt.model == "tuned-agent-model"
    assert final_prompt.model_kwargs == {"temperature": 0.2}
