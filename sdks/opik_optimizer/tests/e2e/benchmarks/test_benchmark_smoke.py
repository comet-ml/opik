import json
from concurrent.futures import Future
from pathlib import Path
from typing import Any
from collections.abc import Callable

from opik_optimizer import ChatPrompt
import opik_optimizer

import pytest

from benchmarks.core import benchmark_config
from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec
from benchmarks.local import runner as local_runner


class InlineExecutor:
    """Synchronous stand-in for ProcessPoolExecutor used in smoke tests."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:  # noqa: D401
        self.submissions: list[tuple] = []

    def __enter__(self) -> "InlineExecutor":
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:  # type: ignore[override]
        return None

    def submit(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Future[Any]:
        result = fn(*args, **kwargs)
        self.submissions.append((fn, args, kwargs, result))
        fut: Future[Any] = Future()
        fut.set_result(result)
        return fut

    def shutdown(self, wait: bool = True, _cancel_futures: bool = False) -> None:
        _ = _cancel_futures
        return None


class DummyDataset:
    def __init__(self, name: str):
        self.name = name
        self.id = f"{name}-id"

    def get_items(self) -> list[dict[str, Any]]:
        return []


class DummyOptimizationResult(dict):
    def __init__(self, prompt_messages: list[dict[str, Any]] | None) -> None:
        prompt_messages = prompt_messages or []
        super().__init__(prompt=prompt_messages, llm_calls=1)
        self.prompt = prompt_messages
        self.llm_calls = 1


class DummyOptimizer:
    def __init__(self, model: str, **kwargs: Any) -> None:
        self.model = model
        self.kwargs = kwargs

    def evaluate_prompt(  # type: ignore[no-untyped-def]
        self, prompt: ChatPrompt, dataset: Any, metric: Any, n_threads: int
    ):
        return metric({}, "")

    def optimize_prompt(  # type: ignore[no-untyped-def]
        self,
        *,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Any,
        validation_dataset: Any | None = None,
        **kwargs: Any,
    ):
        return DummyOptimizationResult(prompt.messages)


def _metric_fn(_dataset_item: dict[str, Any], _llm_output: str) -> float:
    return 0.25


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
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "INITIAL_PROMPTS",
        {
            "toy_train": [{"role": "system", "content": "Say hi to {question}"}],
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
    monkeypatch.setattr(benchmark_config, "MODELS", ["dummy-model"])


def _patch_datasets(monkeypatch: pytest.MonkeyPatch) -> None:
    def toy_loader(
        split: str | None = None, dataset_name: str | None = None, **_: Any
    ) -> DummyDataset:
        if split == "validation" or dataset_name == "toy_validation":
            return DummyDataset("toy_validation")
        if split == "test" or dataset_name == "toy_test":
            return DummyDataset("toy_test")
        return DummyDataset("toy_train")

    monkeypatch.setattr(opik_optimizer.datasets, "toy", toy_loader, raising=False)
    monkeypatch.setattr(opik_optimizer, "DummyOptimizer", DummyOptimizer, raising=False)


@pytest.mark.integration
def test_run_benchmark_smoke(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Smoke test the benchmark runner end-to-end with dummy components."""
    _patch_benchmark_config(monkeypatch)
    _patch_datasets(monkeypatch)

    # Inline executor to avoid multiprocessing in tests
    monkeypatch.setattr(local_runner, "ProcessPoolExecutor", InlineExecutor)

    runner = local_runner.BenchmarkRunner(
        max_workers=1,
        seed=42,
        test_mode=True,
        checkpoint_dir=str(tmp_path),
    )

    task = BenchmarkTaskSpec(
        dataset_name="toy_train",
        optimizer_name="dummy",
        model_name="dummy-model",
        test_mode=True,
    )

    runner.run_benchmarks(
        demo_datasets=["toy_train"],
        optimizers=["dummy"],
        models=["dummy-model"],
        retry_failed_run_id=None,
        resume_run_id=None,
        task_specs=[task],
    )

    # Load the saved checkpoint and ensure the task succeeded
    assert runner.run_id is not None
    checkpoint_file = tmp_path / runner.run_id / "checkpoint.json"
    assert checkpoint_file.exists()
    checkpoint = json.loads(checkpoint_file.read_text())
    task_results = checkpoint.get("task_results", [])
    assert task_results, "Expected a saved task result"
    assert task_results[0]["status"] == "Success"
    assert task_results[0]["dataset_name"] == "toy_train"
    # Ensure evaluation/test metadata are present
    assert "dataset_metadata" in task_results[0]
    assert "evaluations" in task_results[0]
