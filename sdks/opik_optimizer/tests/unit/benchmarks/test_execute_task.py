from typing import Any

import opik_optimizer
from opik_optimizer import ChatPrompt
import pytest

from benchmarks.core import benchmark_config
from benchmarks.utils.task_runner import execute_task, resolve_dataset_bundle


class DummyDataset:
    def __init__(self, name: str) -> None:
        self.name = name
        self.id = f"{name}-id"


class DummyOptimizationResult:
    def __init__(self, prompt_messages: list[dict[str, Any]]) -> None:
        self.prompt = prompt_messages
        self.llm_calls = 3


class DummyOptimizer:
    def __init__(self, model: str, **kwargs: Any) -> None:
        self.model = model
        self.kwargs = kwargs
        self.calls: list[tuple] = []

    def evaluate_prompt(  # type: ignore[no-untyped-def]
        self, prompt, dataset, metric, n_threads
    ):
        metric_name = getattr(metric, "__name__", "metric")
        self.calls.append(("evaluate", dataset.name, metric_name))
        return metric({}, "")

    def optimize_prompt(  # type: ignore[no-untyped-def]
        self,
        *,
        prompt,
        dataset,
        metric,
        validation_dataset=None,
        **kwargs: Any,
    ):
        self.calls.append(
            (
                "optimize",
                dataset.name,
                getattr(validation_dataset, "name", None),
                kwargs,
            )
        )
        return DummyOptimizationResult(prompt.messages)


def _metric_fn(_dataset_item: dict[str, Any], _llm_output: str) -> float:
    return 0.5


def _patch_benchmark_config(monkeypatch: pytest.MonkeyPatch) -> None:
    dataset_cfg = benchmark_config.BenchmarkDatasetConfig(
        name="toy_train",
        display_name="Toy",
        metrics=[_metric_fn],
        rollout_budget=10,
        train_rollout_budget=4,
    )
    monkeypatch.setattr(
        benchmark_config,
        "DATASET_CONFIG",
        {
            "toy_train": dataset_cfg,
            "toy_validation": dataset_cfg,
            "toy_test": dataset_cfg,
            "simple": dataset_cfg,
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "INITIAL_PROMPTS",
        {
            "toy_train": [{"role": "user", "content": "{question}"}],
            "simple": [{"role": "user", "content": "{question}"}],
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "OPTIMIZER_CONFIGS",
        {
            "dummy": benchmark_config.BenchmarkOptimizerConfig(
                class_name="DummyOptimizer",
                params={},
                optimizer_prompt_params={"max_trials": 5, "n_samples": 2},
            )
        },
    )


def _patch_datasets(monkeypatch: pytest.MonkeyPatch) -> DummyOptimizer:
    def toy_loader(
        split: str | None = None, dataset_name: str | None = None, **_: Any
    ) -> DummyDataset:
        if split == "validation" or dataset_name == "toy_validation":
            return DummyDataset("toy_validation")
        if split == "test" or dataset_name == "toy_test":
            return DummyDataset("toy_test")
        return DummyDataset("toy_train")

    def simple_loader(**_: Any) -> DummyDataset:
        return DummyDataset("simple")

    monkeypatch.setattr(opik_optimizer.datasets, "toy", toy_loader, raising=False)
    monkeypatch.setattr(opik_optimizer.datasets, "simple", simple_loader, raising=False)
    dummy_opt = DummyOptimizer("dummy-model")
    monkeypatch.setattr(opik_optimizer, "DummyOptimizer", DummyOptimizer, raising=False)
    return dummy_opt


def test_execute_task_uses_validation_and_test_splits(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_benchmark_config(monkeypatch)
    _patch_datasets(monkeypatch)

    result = execute_task(
        task_id="task1",
        dataset_name="toy_train",
        optimizer_name="dummy",
        model_name="model-x",
        model_parameters=None,
        test_mode=False,
        optimizer_params_override=None,
        optimizer_prompt_params_override=None,
    )

    assert result.evaluation_split == "validation"
    init_val = result.evaluations["initial"].validation
    assert init_val is not None and init_val.result is not None
    assert init_val.result.dataset and init_val.result.dataset.split == "validation"
    init_test = result.evaluations["initial"].test
    assert init_test is not None and init_test.result is not None
    assert init_test.result.dataset and init_test.result.dataset.split == "test"
    assert set(result.dataset_metadata.keys()) == {"train", "validation", "test"}
    assert result.optimized_prompt and isinstance(result.optimized_prompt, ChatPrompt)


def test_execute_task_without_validation(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_benchmark_config(monkeypatch)
    _patch_datasets(monkeypatch)

    result = execute_task(
        task_id="task2",
        dataset_name="simple",
        optimizer_name="dummy",
        model_name="model-y",
        model_parameters=None,
        test_mode=True,
        optimizer_params_override=None,
        optimizer_prompt_params_override=None,
    )

    assert result.evaluation_split == "train"
    initial_set = result.evaluations.get("initial")
    assert initial_set is not None
    assert initial_set.test is None
    assert (
        "train" in result.dataset_metadata
        and "validation" not in result.dataset_metadata
    )


def test_resolve_dataset_bundle_raises_for_unknown(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_benchmark_config(monkeypatch)
    with pytest.raises(ValueError):
        resolve_dataset_bundle("unknown_dataset", test_mode=False)
