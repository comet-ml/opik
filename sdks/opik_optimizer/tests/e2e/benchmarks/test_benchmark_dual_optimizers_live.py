import json
import os
from concurrent.futures import Future
from pathlib import Path
from typing import Any
from collections.abc import Callable

import opik_optimizer
import pytest
from opik.evaluation.metrics.heuristics.equals import Equals

from benchmarks.core import benchmark_config
from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec
from benchmarks.local import runner as local_runner

pytestmark = pytest.mark.integration


class InlineExecutor:
    """Synchronous stand-in to keep the benchmark runner single-process in tests."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        self.submissions: list[tuple] = []

    def __enter__(self) -> "InlineExecutor":  # pragma: no cover - trivial
        return self

    def __exit__(
        self, exc_type: Any, exc: Any, tb: Any
    ) -> None:  # pragma: no cover - trivial
        return None

    def submit(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Future[Any]:
        result = fn(*args, **kwargs)
        fut: Future[Any] = Future()
        fut.set_result(result)
        return fut

    def shutdown(
        self, wait: bool = True, _cancel_futures: bool = False
    ) -> None:  # pragma: no cover - trivial
        _ = _cancel_futures
        return None


def _skip_without_openai() -> None:
    if not os.getenv("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY is required for live benchmark smoke test.")


def _metric_equals(dataset_item: dict[str, Any], llm_output: str) -> float:
    return Equals().score(reference=dataset_item["label"], output=llm_output).value


def _patch_benchmark_config(monkeypatch: pytest.MonkeyPatch) -> None:
    dataset_cfg = benchmark_config.BenchmarkDatasetConfig(
        name="tiny_test",
        display_name="Tiny Test Live",
        metrics=[_metric_equals],
        rollout_budget=2,
        train_rollout_budget=2,
    )
    monkeypatch.setattr(
        benchmark_config,
        "DATASET_CONFIG",
        {
            "tiny_test": dataset_cfg,
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "INITIAL_PROMPTS",
        {
            "tiny_test": [
                {
                    "role": "system",
                    "content": "Answer the question correctly and concisely.",
                },
                {"role": "user", "content": "{text}"},
            ]
        },
    )
    monkeypatch.setattr(
        benchmark_config,
        "OPTIMIZER_CONFIGS",
        {
            "few_shot": benchmark_config.BenchmarkOptimizerConfig(
                class_name="FewShotBayesianOptimizer",
                params={
                    "min_examples": 1,
                    "max_examples": 2,
                    "n_threads": 1,
                    "seed": 42,
                },
                optimizer_prompt_params={"max_trials": 1, "n_samples": 1},
            ),
            "evolutionary_optimizer": benchmark_config.BenchmarkOptimizerConfig(
                class_name="EvolutionaryOptimizer",
                params={
                    "mutation_rate": 0.2,
                    "crossover_rate": 0.8,
                    "tournament_size": 2,
                    "n_threads": 1,
                    "elitism_size": 1,
                    "adaptive_mutation": False,
                    "enable_moo": False,
                    "enable_llm_crossover": False,
                    "seed": 42,
                    "infer_output_style": True,
                },
                optimizer_prompt_params={
                    "max_trials": 1,
                    "population_size": 2,
                    "num_generations": 1,
                },
            ),
        },
    )
    monkeypatch.setattr(benchmark_config, "MODELS", ["openai/gpt-4o-mini"])


def _patch_dataset_loader(monkeypatch: pytest.MonkeyPatch) -> None:
    real_tiny_test = opik_optimizer.datasets.tiny_test

    def tiny_test_loader(
        split: str | None = None,
        count: int | None = None,
        dataset_name: str | None = None,
        **kwargs: Any,
    ) -> Any:
        return real_tiny_test(
            split=split,
            count=1 if count is None else count,
            dataset_name=dataset_name or "tiny_test_live",
            **kwargs,
        )

    monkeypatch.setattr(
        opik_optimizer.datasets, "tiny_test", tiny_test_loader, raising=False
    )


def test_dual_optimizer_run_live(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Run two benchmark tasks (few_shot + evolutionary) against tiny_test with a live model."""
    _skip_without_openai()
    _patch_benchmark_config(monkeypatch)
    _patch_dataset_loader(monkeypatch)
    monkeypatch.setattr(local_runner, "ProcessPoolExecutor", InlineExecutor)

    runner = local_runner.BenchmarkRunner(
        max_workers=1,
        seed=42,
        test_mode=True,
        checkpoint_dir=str(tmp_path),
    )

    tasks = [
        BenchmarkTaskSpec(
            dataset_name="tiny_test",
            optimizer_name="few_shot",
            model_name="openai/gpt-4o-mini",
            test_mode=True,
            optimizer_prompt_params={"max_trials": 1, "n_samples": 1},
        ),
        BenchmarkTaskSpec(
            dataset_name="tiny_test",
            optimizer_name="evolutionary_optimizer",
            model_name="openai/gpt-4o-mini",
            test_mode=True,
            optimizer_prompt_params={
                "max_trials": 1,
                "population_size": 2,
                "num_generations": 1,
            },
        ),
    ]

    runner.run_benchmarks(
        demo_datasets=["tiny_test"],
        optimizers=["few_shot", "evolutionary_optimizer"],
        models=["openai/gpt-4o-mini"],
        retry_failed_run_id=None,
        resume_run_id=None,
        task_specs=tasks,
    )

    assert runner.run_id is not None
    checkpoint_file = tmp_path / runner.run_id / "checkpoint.json"
    assert checkpoint_file.exists()
    checkpoint = json.loads(checkpoint_file.read_text())
    task_results = checkpoint.get("task_results", [])
    statuses = {t["optimizer_name"]: t["status"] for t in task_results}
    assert statuses.get("few_shot") == "Success"
    assert statuses.get("evolutionary_optimizer") == "Success"
