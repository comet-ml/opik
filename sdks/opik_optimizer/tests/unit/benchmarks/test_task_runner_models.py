from typing import Any

import pytest

from benchmarks.utils import task_runner
from benchmarks.packages import registry as benchmark_config
from benchmarks.core.types import TaskResult
from opik_optimizer import ChatPrompt
from tests.unit.fixtures import user_message


class DummyOptimizer:
    def __init__(self, model: str, model_parameters: dict[str, Any] | None = None):
        self.model = f"{model}-from-optimizer"
        self.model_parameters = {**(model_parameters or {}), "source": "optimizer"}
        self.llm_calls = 0

    def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
        # Return an object with a prompt attribute and llm_calls to mimic optimizers
        class Result:
            def __init__(self) -> None:
                self.prompt = [user_message("hi")]
                self.llm_calls = 0
                self.history: list[dict[str, Any]] = []

        return Result()

    def evaluate_prompt(self, *args: Any, **kwargs: Any) -> float:
        return 0.0


class DummyOptimizerWithHistory(DummyOptimizer):
    def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
        class Result:
            def __init__(self) -> None:
                self.prompt = [user_message("hi")]
                self.llm_calls = 3
                self.history = [
                    {
                        "round_index": 0,
                        "trials": [{"trial_index": 0, "score": 0.1}],
                        "best_score": 0.1,
                    }
                ]

        return Result()


def test_chatprompt_uses_optimizer_model(monkeypatch: pytest.MonkeyPatch) -> None:
    # Patch config to a minimal dataset/optimizer to bypass irrelevant logic
    monkeypatch.setattr(
        benchmark_config,
        "DATASET_CONFIG",
        {
            "dummy": benchmark_config.BenchmarkDatasetConfig(
                name="dummy", display_name="Dummy", metrics=[lambda *_: 0.0]
            )
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "OPTIMIZER_CONFIGS",
        {
            "dummy_opt": benchmark_config.BenchmarkOptimizerConfig(
                class_name="DummyOptimizer", params={}
            )
        },
    )

    # Stub resolve_dataset_bundle to return minimal datasets
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

    # Stub optimizer class lookup to use DummyOptimizer
    # Inject DummyOptimizer into the opik_optimizer namespace used by task_runner
    import types

    fake_module = types.SimpleNamespace(**task_runner.opik_optimizer.__dict__)
    setattr(fake_module, "DummyOptimizer", DummyOptimizer)
    monkeypatch.setattr(task_runner, "opik_optimizer", fake_module)

    result = task_runner.execute_task(
        task_id="t1",
        dataset_name="dummy",
        optimizer_name="dummy_opt",
        model_name="custom-model",
        model_parameters={"temperature": 0.7},
        test_mode=False,
        optimizer_params_override=None,
        optimizer_prompt_params_override=None,
        datasets=None,
        metrics=["builtins.abs"],
        prompt_messages=[user_message("{text}")],
    )

    assert isinstance(result, TaskResult)
    assert isinstance(result.initial_prompt, ChatPrompt)
    assert result.initial_prompt.model == "custom-model-from-optimizer"
    assert result.initial_prompt.model_kwargs.get("temperature") == 0.7
    assert result.initial_prompt.model_kwargs.get("source") == "optimizer"
    assert isinstance(result.optimized_prompt, ChatPrompt)
    assert result.optimized_prompt.model == "custom-model-from-optimizer"
    assert result.optimized_prompt.model_kwargs.get("temperature") == 0.7
    assert result.optimized_prompt.model_kwargs.get("source") == "optimizer"


def test_task_result_prefers_optimizer_native_history(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        benchmark_config,
        "DATASET_CONFIG",
        {
            "dummy": benchmark_config.BenchmarkDatasetConfig(
                name="dummy", display_name="Dummy", metrics=[lambda *_: 0.0]
            )
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "OPTIMIZER_CONFIGS",
        {
            "dummy_opt": benchmark_config.BenchmarkOptimizerConfig(
                class_name="DummyOptimizerWithHistory", params={}
            )
        },
    )

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

    import types

    fake_module = types.SimpleNamespace(**task_runner.opik_optimizer.__dict__)
    setattr(fake_module, "DummyOptimizerWithHistory", DummyOptimizerWithHistory)
    monkeypatch.setattr(task_runner, "opik_optimizer", fake_module)

    result = task_runner.execute_task(
        task_id="t2",
        dataset_name="dummy",
        optimizer_name="dummy_opt",
        model_name="custom-model",
        model_parameters={"temperature": 0.2},
        test_mode=False,
        optimizer_params_override=None,
        optimizer_prompt_params_override=None,
        datasets=None,
        metrics=["builtins.abs"],
        prompt_messages=[user_message("{text}")],
    )

    rounds = result.optimization_history.get("rounds", [])
    assert len(rounds) == 1
    assert rounds[0]["round_index"] == 0
    assert result.llm_calls_total_optimization == 3
