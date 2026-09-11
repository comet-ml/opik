from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from benchmarks.core.manifest import load_manifest, manifest_to_task_specs
from benchmarks.packages import registry as benchmark_config
from benchmarks.core.types import TaskSpec


@dataclass(frozen=True)
class TaskPlan:
    tasks: list[TaskSpec]
    demo_datasets: list[str]
    optimizers: list[str]
    models: list[str]
    seed: int
    test_mode: bool
    max_concurrent: int
    checkpoint_dir: str
    auto_confirm: bool = False
    manifest_path: str | None = None
    retry_failed_run_id: str | None = None
    resume_run_id: str | None = None


@dataclass(frozen=True)
class PlanInput:
    demo_datasets: list[str] | None
    optimizers: list[str] | None
    models: list[str] | None
    seed: int
    test_mode: bool
    max_concurrent: int
    checkpoint_dir: str
    config_path: str | None
    retry_failed_run_id: str | None
    resume_run_id: str | None
    auto_confirm: bool = False


def compile_task_plan(data: PlanInput) -> TaskPlan:
    manifest_tasks: list[TaskSpec] | None = None
    seed = data.seed
    test_mode = data.test_mode

    if data.config_path:
        if data.demo_datasets or data.optimizers or data.models:
            raise ValueError(
                "Cannot combine --config with demo_datasets/optimizers/models filters; "
                "remove filters or the config flag."
            )
        manifest = load_manifest(data.config_path)
        manifest_tasks = manifest_to_task_specs(
            manifest, fallback_test_mode=data.test_mode
        )
        if not manifest_tasks:
            raise ValueError("Manifest must contain at least one task.")
        if manifest.seed is not None:
            seed = manifest.seed
        if manifest.test_mode is not None:
            test_mode = manifest.test_mode

    if manifest_tasks is not None:
        tasks = manifest_tasks
        demo_datasets = sorted({task.dataset_name for task in tasks})
        optimizers = sorted({task.optimizer_name for task in tasks})
        models = sorted({task.model_name for task in tasks})
    else:
        demo_datasets = data.demo_datasets or list(
            benchmark_config.DATASET_CONFIG.keys()
        )
        optimizers = data.optimizers or list(benchmark_config.OPTIMIZER_CONFIGS.keys())
        models = data.models or list(benchmark_config.MODELS)
        tasks = [
            TaskSpec(
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                test_mode=test_mode,
            )
            for dataset_name in demo_datasets
            for optimizer_name in optimizers
            for model_name in models
        ]

    return TaskPlan(
        tasks=tasks,
        demo_datasets=demo_datasets,
        optimizers=optimizers,
        models=models,
        seed=seed,
        test_mode=test_mode,
        max_concurrent=data.max_concurrent,
        checkpoint_dir=data.checkpoint_dir,
        auto_confirm=data.auto_confirm,
        manifest_path=data.config_path,
        retry_failed_run_id=data.retry_failed_run_id,
        resume_run_id=data.resume_run_id,
    )


def summarize_plan(plan: TaskPlan) -> dict[str, Any]:
    return {
        "tasks": len(plan.tasks),
        "datasets": plan.demo_datasets,
        "optimizers": plan.optimizers,
        "models": plan.models,
        "seed": plan.seed,
        "test_mode": plan.test_mode,
        "manifest_path": plan.manifest_path,
    }
