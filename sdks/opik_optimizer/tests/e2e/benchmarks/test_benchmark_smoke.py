import json
from pathlib import Path
from typing import Any

from opik_optimizer import ChatPrompt
import opik_optimizer

import pytest

from benchmarks.packages import registry as benchmark_config
from benchmarks.core.types import TaskSpec
from benchmarks.engines.local import engine as local_engine
from tests.e2e.optimizers.utils import system_message
from ._benchmark_test_helpers import InlineExecutor


class DummyDataset:
    def __init__(self, name: str):
        self.name = name
        self.id = f"{name}-id"

    def get_items(self) -> list[dict[str, Any]]:
        return []


class DummyOptimizationResult(dict):
    def __init__(self, prompt_messages: list[dict[str, Any]] | None) -> None:
        prompt_messages = prompt_messages or []
        super().__init__(prompt=prompt_messages, llm_calls=1, history=[])
        self.prompt = prompt_messages
        self.llm_calls = 1
        self.history: list[dict[str, Any]] = []


class DummyOptimizer:
    def __init__(self, model: str, **kwargs: Any) -> None:
        self.model = model
        self.kwargs = kwargs

    def evaluate_prompt(  # type: ignore[no-untyped-def]
        self,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Any,
        n_threads: int,
        agent: Any | None = None,
        **_: Any,
    ):
        return metric({}, "")

    def optimize_prompt(  # type: ignore[no-untyped-def]
        self,
        *,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Any,
        validation_dataset: Any | None = None,
        agent: Any | None = None,
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
            "toy_train": [system_message("Say hi to {question}")],
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "OPTIMIZER_CONFIGS",
        {
            "dummy": benchmark_config.BenchmarkOptimizerConfig(
                class_name="DummyOptimizer",
                params={},
                optimizer_prompt_params={"max_trials": 1, "n_samples": 1},
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

    # Ensure benchmark logging writes into the tmp path to avoid permission errors.
    monkeypatch.setenv("HOME", str(tmp_path))

    # Inline executor to avoid multiprocessing in tests
    monkeypatch.setattr(local_engine, "ProcessPoolExecutor", InlineExecutor)

    runner = local_engine.BenchmarkRunner(
        max_workers=1,
        seed=42,
        test_mode=True,
        checkpoint_dir=str(tmp_path),
    )

    task = TaskSpec(
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
