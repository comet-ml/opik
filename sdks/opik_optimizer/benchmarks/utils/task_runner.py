"""Shared helpers for running benchmark tasks locally or on Modal."""

from __future__ import annotations

import time
import traceback
from dataclasses import dataclass
from typing import Any
from collections.abc import Callable
import warnings
import importlib
from typing import cast
import os
import logging
from datetime import datetime
import importlib.metadata
import hashlib

from benchmarks.core import benchmark_config
from benchmarks.core.benchmark_config import BenchmarkDatasetConfig
import opik_optimizer.datasets
from benchmarks.core.benchmark_task import (
    DatasetMetadata,
    TaskEvaluationResult,
    EvaluationSet,
    EvaluationStage,
    TaskResult,
    TASK_STATUS_FAILED,
    TASK_STATUS_SUCCESS,
)
from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec
from opik_optimizer import BaseOptimizer, ChatPrompt, reporting_utils
from benchmarks.local.logging import console
from rich.table import Table
from rich.panel import Panel
from rich.console import Group
from rich import box
from rich.text import Text
from benchmarks.core.benchmark_results import (
    PreflightContext,
    PreflightEntry,
    PreflightReport,
    PreflightSummary,
)


_SPLIT_SUFFIXES = {
    "train": "_train",
    "validation": "_validation",
    "test": "_test",
}

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DatasetBundle:
    """Resolved dataset bundle for a benchmark task."""

    train_name: str
    train: Any
    validation_name: str | None
    validation: Any | None
    test_name: str | None
    test: Any | None
    evaluation_name: str
    evaluation_role: str
    evaluation: Any
    requested_split: str | None


def _parse_base_name(dataset_name: str) -> tuple[str, str | None]:
    for split, suffix in _SPLIT_SUFFIXES.items():
        if dataset_name.endswith(suffix):
            return dataset_name[: -len(suffix)], split
    return dataset_name, None


def _resolve_initial_prompt(dataset_name: str) -> list[dict[str, Any]]:
    """
    Return an initial prompt for a dataset, tolerating sample-suffixed names.

    Tries, in order:
      1) Exact dataset_name
      2) Base name (stripping _train/_validation/_test)
      3) Base name + '_train'
    """
    # Exact match
    if dataset_name in benchmark_config.INITIAL_PROMPTS:
        return benchmark_config.INITIAL_PROMPTS[dataset_name]

    base, _ = _parse_base_name(dataset_name)

    # Base name
    if base in benchmark_config.INITIAL_PROMPTS:
        return benchmark_config.INITIAL_PROMPTS[base]

    # Base train fallback
    candidate = f"{base}_train"
    if candidate in benchmark_config.INITIAL_PROMPTS:
        return benchmark_config.INITIAL_PROMPTS[candidate]

    raise KeyError(f"No initial prompt configured for dataset '{dataset_name}'")


def _load_dataset(
    dataset_name: str, split: str | None, test_mode: bool, *, dry_run: bool = False
) -> Any:
    """Load a dataset by name, falling back to base loader when split-specific helpers are absent."""
    if dry_run:
        return None
    loader = getattr(opik_optimizer.datasets, dataset_name, None)
    if callable(loader):
        return loader(test_mode=test_mode)

    base_name, _ = _parse_base_name(dataset_name)
    base_loader = getattr(opik_optimizer.datasets, base_name, None)
    if callable(base_loader):
        kwargs: dict[str, Any] = {"test_mode": test_mode}
        if split in ("train", "validation", "test"):
            kwargs["split"] = split
        kwargs["dataset_name"] = dataset_name
        if "prefer_presets" in base_loader.__code__.co_varnames:
            kwargs["prefer_presets"] = True
        return base_loader(**kwargs)

    raise ValueError(f"Unknown dataset loader for '{dataset_name}'.")


def resolve_dataset_bundle(
    dataset_name: str,
    test_mode: bool,
    datasets: dict[str, Any] | None = None,
    *,
    dry_run: bool = False,
) -> DatasetBundle:
    """Return train/validation/test dataset objects for a given benchmark dataset key.

    When ``datasets`` is provided, the loader kwargs (train/validation/test)
    are used instead of the registered preset slices. If only one override is
    given, it is reused for all splits (with a warning).
    """
    if datasets:
        if "train" not in datasets and any(
            k in datasets for k in ("validation", "test")
        ):
            raise ValueError(
                "datasets config must include a train split when validation/test are provided."
            )

        explicit_roles = any(
            role in datasets for role in ("train", "validation", "test")
        )
        if explicit_roles:
            role_specs = datasets
        else:
            # If the user provided a single override object, apply it to train only.
            # Callers should explicitly specify validation/test if they need them.
            role_specs = {"train": datasets}
            warnings.warn(
                "Dataset overrides provided without explicit splits; applying overrides to train only "
                "and skipping validation/test.",
                stacklevel=2,
            )

        def _load_override(role: str) -> tuple[str, Any] | tuple[None, None]:
            spec = role_specs.get(role)
            if spec is None:
                return None, None
            loader_name = spec.get("loader") if isinstance(spec, dict) else None
            kwargs = dict(spec) if isinstance(spec, dict) else {}
            loader_name = loader_name or dataset_name
            kwargs.pop("loader", None)
            kwargs.setdefault("dataset_name", f"{loader_name}_{role}")
            if role in ("train", "validation", "test"):
                kwargs.setdefault("split", role)
            kwargs["test_mode"] = test_mode
            loader = getattr(opik_optimizer.datasets, loader_name, None)
            if callable(loader):
                return (
                    kwargs["dataset_name"],
                    None if dry_run else loader(**kwargs),
                )
            raise ValueError(
                f"Unknown dataset loader '{loader_name}' for role '{role}'."
            )

        train_name, train_ds = _load_override("train")
        validation_name, validation_ds = _load_override("validation")
        test_name, test_ds = _load_override("test")

        evaluation_ds = validation_ds or train_ds
        evaluation_name = validation_name or train_name or dataset_name
        evaluation_role = "validation" if validation_ds is not None else "train"

        return DatasetBundle(
            train_name=train_name or dataset_name,
            train=train_ds,
            validation_name=validation_name,
            validation=validation_ds,
            test_name=test_name,
            test=test_ds,
            evaluation_name=evaluation_name,
            evaluation_role=evaluation_role,
            evaluation=evaluation_ds,
            requested_split=None,
        )

    base_name, requested_split = _parse_base_name(dataset_name)

    def _candidate(split: str) -> str | None:
        candidate_name = f"{base_name}_{split}"
        return (
            candidate_name
            if candidate_name in benchmark_config.DATASET_CONFIG
            else None
        )

    train_name = _candidate("train") or dataset_name
    validation_name = _candidate("validation")
    test_name = _candidate("test")

    if train_name not in benchmark_config.DATASET_CONFIG:
        raise ValueError(
            f"Dataset '{dataset_name}' is not registered in benchmark_config.DATASET_CONFIG."
        )

    train_dataset = _load_dataset(
        train_name, "train", test_mode=test_mode, dry_run=dry_run
    )
    validation_dataset = (
        _load_dataset(
            validation_name, "validation", test_mode=test_mode, dry_run=dry_run
        )
        if validation_name
        else None
    )
    test_dataset = (
        _load_dataset(test_name, "test", test_mode=test_mode, dry_run=dry_run)
        if test_name
        else None
    )

    evaluation_dataset = validation_dataset or train_dataset
    evaluation_name = validation_name or train_name
    evaluation_role = "validation" if validation_dataset is not None else "train"

    return DatasetBundle(
        train_name=train_name,
        train=train_dataset,
        validation_name=validation_name,
        validation=validation_dataset,
        test_name=test_name,
        test=test_dataset,
        evaluation_name=evaluation_name,
        evaluation_role=evaluation_role,
        evaluation=evaluation_dataset,
        requested_split=requested_split,
    )


def _safe_version(pkg: str) -> str | None:
    try:
        return importlib.metadata.version(pkg)
    except Exception:
        return None


def preflight_tasks(
    task_specs: list[BenchmarkTaskSpec], info: dict[str, Any] | None = None
) -> PreflightReport:
    """Validate datasets/metrics/optimizers before scheduling to fail fast."""
    errors: list[str] = []
    had_error = False
    datasets_seen: set[str] = set()
    optimizers_seen: set[str] = set()
    models_seen: set[str] = set()
    entries: list[PreflightEntry] = []

    logger.info("🔎 Preflight: validating %d tasks", len(task_specs))
    console.print(
        f"[bold blue]Preflight:[/bold blue] validating {len(task_specs)} tasks"
    )

    info_table = Table(show_header=False, padding=(0, 1))
    now_iso = datetime.now().isoformat(timespec="seconds")
    info_table.add_row("System time", now_iso)
    info_table.add_row("CWD", os.getcwd())
    manifest_path = None
    checkpoint_dir = None
    run_id = None
    if info:
        manifest_path = info.get("manifest_path")
        checkpoint_dir = info.get("checkpoint_dir")
        run_id = info.get("run_id")
    info_table.add_row("Manifest", manifest_path or "[dim]N/A[/dim]")
    info_table.add_row("Checkpoint", checkpoint_dir or "[dim]N/A[/dim]")
    info_table.add_row("Run ID", run_id or "[dim]N/A[/dim]")
    info_table.add_row("opik", _safe_version("opik") or "[dim]unknown[/dim]")
    info_table.add_row(
        "opik_optimizer", _safe_version("opik-optimizer") or "[dim]unknown[/dim]"
    )

    def _role_display(
        role: str,
        ds_name: str | None,
        spec: dict[str, Any] | None,
        present: bool,
    ) -> str:
        # Prefer explicit dataset_name in the manifest, then fall back to the loader,
        # then whatever name came from the resolved bundle.
        base = None
        if spec and isinstance(spec, dict):
            base = spec.get("dataset_name") or spec.get("loader")
        base = base or ds_name
        if spec is not None and isinstance(spec, dict):
            count = spec.get("count")
            if count is not None:
                return f"{role}={base or 'None'}({count})"
        if not present:
            return f"{role}=None"
        return f"{role}={base or 'None'}"

    def _format_splits(bundle: DatasetBundle, task: BenchmarkTaskSpec) -> str:
        """Human-friendly split summary with dataset names and counts."""
        tokens: list[str] = []
        train_spec = task.datasets.get("train") if task.datasets else None
        val_spec = task.datasets.get("validation") if task.datasets else None
        test_spec = task.datasets.get("test") if task.datasets else None

        tokens.append(
            _role_display(
                "train", bundle.train_name, train_spec, bundle.train is not None
            )
        )
        tokens.append(
            _role_display(
                "val", bundle.validation_name, val_spec, bundle.validation is not None
            )
        )
        tokens.append(
            _role_display("test", bundle.test_name, test_spec, bundle.test is not None)
        )
        return ", ".join(tokens)

    for task in task_specs:
        if task.optimizer_name not in benchmark_config.OPTIMIZER_CONFIGS:
            msg = f"Unknown optimizer '{task.optimizer_name}'"
            logger.error(msg)
            errors.append(msg)
            had_error = True
            entries.append(
                PreflightEntry(
                    task_id=task.task_id,
                    short_id=hashlib.sha1(
                        f"{run_id or 'run'}:{task.task_id}".encode()
                    ).hexdigest()[:5],
                    dataset_name=task.dataset_name,
                    evaluation_name=None,
                    optimizer_name=task.optimizer_name,
                    model_name=task.model_name,
                    status="error",
                    splits=None,
                    error=msg,
                )
            )
            continue

        try:
            bundle = resolve_dataset_bundle(
                dataset_name=task.dataset_name,
                test_mode=task.test_mode,
                datasets=task.datasets,
                dry_run=True,
            )
            split_summary = _format_splits(bundle, task)
            dataset_config = benchmark_config.DATASET_CONFIG.get(
                bundle.evaluation_name,
                benchmark_config.DATASET_CONFIG.get(task.dataset_name),
            )
            if dataset_config is None:
                raise ValueError(
                    f"Dataset '{task.dataset_name}' is not registered in benchmark_config.DATASET_CONFIG."
                )
            _resolve_metrics(
                dataset_config,
                cast(list[str | dict[str, Any]] | None, task.metrics),
            )
            datasets_seen.add(bundle.evaluation_name or task.dataset_name)
            optimizers_seen.add(task.optimizer_name)
            models_seen.add(task.model_name)
            short_id = hashlib.sha1(
                f"{run_id or 'run'}:{task.task_id}".encode()
            ).hexdigest()[:5]
            entries.append(
                PreflightEntry(
                    task_id=task.task_id,
                    short_id=short_id,
                    dataset_name=task.dataset_name,
                    evaluation_name=bundle.evaluation_name,
                    optimizer_name=task.optimizer_name,
                    model_name=task.model_name,
                    status="ok",
                    splits=split_summary,
                    error=None,
                )
            )
            logger.info(
                "✅ Preflight ok: dataset=%s (eval=%s) optimizer=%s model=%s",
                task.dataset_name,
                bundle.evaluation_name,
                task.optimizer_name,
                task.model_name,
            )
        except Exception as exc:
            err = f"Preflight failed for dataset '{task.dataset_name}': {exc}"
            logger.error(err)
            errors.append(err)
            had_error = True
            short_id = hashlib.sha1(
                f"{run_id or 'run'}:{task.task_id}".encode()
            ).hexdigest()[:5]
            entries.append(
                PreflightEntry(
                    task_id=task.task_id,
                    short_id=short_id,
                    dataset_name=task.dataset_name,
                    evaluation_name=None,
                    optimizer_name=task.optimizer_name,
                    model_name=task.model_name,
                    status="error",
                    splits=None,
                    error=str(exc),
                )
            )

    summary = PreflightSummary(
        total_tasks=len(task_specs),
        datasets=sorted(datasets_seen),
        optimizers=sorted(optimizers_seen),
        models=sorted(models_seen),
        errors=errors,
    )
    report = PreflightReport(
        context=PreflightContext(
            system_time=now_iso,
            cwd=os.getcwd(),
            manifest_path=manifest_path,
            checkpoint_dir=checkpoint_dir,
            run_id=run_id,
            opik_version=_safe_version("opik"),
            opik_optimizer_version=_safe_version("opik-optimizer"),
        ),
        summary=summary,
        entries=entries,
    )

    # Render lines (two-line per entry)
    task_lines: list[Text] = [Text("Tasks Preflight:", style="bold"), Text("")]
    for idx, entry in enumerate(entries, 1):
        icon = "[green]✓[/green]" if entry.status == "ok" else "[red]✗[/red]"
        line1 = Text.from_markup(
            f"{icon} ([dim]#[bold]{idx}[/bold] {entry.short_id}[/dim]) "
            f"[bold]{entry.dataset_name}[/bold] | "
            f"[cyan]{entry.optimizer_name}[/cyan] | "
            f"[magenta]{entry.model_name}[/magenta]"
        )
        splits_text = entry.splits or "train=None, val=None, test=None"
        line2 = Text.from_markup(f"    {splits_text}")
        if entry.error:
            line2.append(f" • {entry.error}", style="red")
        task_lines.append(line1)
        task_lines.append(line2)

    summary_table = Table(
        show_header=False,
        padding=(0, 1),
        box=box.SIMPLE,
        expand=True,
    )
    summary_table.add_row(
        "Status",
        "[green]Preflight passed[/green]"
        if not had_error
        else "[red]Preflight failed[/red]",
    )
    summary_table.add_row("Tasks", str(summary.total_tasks))
    summary_table.add_row(
        "Datasets",
        ", ".join(summary.datasets) if summary.datasets else "[dim]-[/dim]",
    )
    summary_table.add_row(
        "Optimizers",
        ", ".join(summary.optimizers) if summary.optimizers else "[dim]-[/dim]",
    )
    summary_table.add_row(
        "Models", ", ".join(summary.models) if summary.models else "[dim]-[/dim]"
    )
    console.print(
        Panel(
            Group(info_table, *task_lines, summary_table),
            title="Preflight",
            border_style="green" if not had_error else "red",
        )
    )

    if had_error:
        raise ValueError("Benchmark preflight checks failed:\n- " + "\n- ".join(errors))
    return report


def _dataset_metadata(dataset: Any, dataset_name: str, role: str) -> DatasetMetadata:
    return DatasetMetadata(
        name=getattr(dataset, "name", dataset_name),
        id=getattr(dataset, "id", None),
        split=role,
    )


def _resolve_metrics(
    dataset_config: BenchmarkDatasetConfig,
    custom_metrics: list[str | dict[str, Any]] | None,
) -> list[Callable]:
    if not custom_metrics:
        return dataset_config.metrics

    resolved: list[Callable] = []
    for entry in custom_metrics:
        path: str
        args: list[Any] = []
        kwargs: dict[str, Any] = {}
        if isinstance(entry, str):
            path = entry
        elif isinstance(entry, dict):
            path = str(entry.get("path"))
            args = entry.get("args", []) or []
            kwargs = entry.get("kwargs", {}) or {}
        else:
            raise ValueError(
                "Metrics entries must be strings or objects with a 'path' key."
            )

        module_path, _, attr = path.rpartition(".")
        if not module_path or not attr:
            raise ValueError(
                f"Invalid metric path '{path}'. Expected module.attr format."
            )
        module = importlib.import_module(module_path)
        metric_obj = getattr(module, attr, None)
        if metric_obj is None:
            raise ValueError(f"Metric '{path}' not found.")

        metric_fn = (
            metric_obj(*args, **kwargs)
            if (args or kwargs) and callable(metric_obj)
            else metric_obj
        )
        if not callable(metric_fn):
            raise ValueError(
                f"Metric '{path}' is not callable after applying args/kwargs."
            )
        resolved.append(metric_fn)
    return resolved


def collect_dataset_metadata(bundle: DatasetBundle) -> dict[str, DatasetMetadata]:
    """Build a metadata map keyed by split name."""
    metadata = {"train": _dataset_metadata(bundle.train, bundle.train_name, "train")}
    if bundle.validation is not None and bundle.validation_name:
        metadata["validation"] = _dataset_metadata(
            bundle.validation, bundle.validation_name, "validation"
        )
    if bundle.test is not None and bundle.test_name:
        metadata["test"] = _dataset_metadata(bundle.test, bundle.test_name, "test")
    return metadata


def evaluate_prompt_on_dataset(
    *,
    optimizer: BaseOptimizer,
    prompt: ChatPrompt,
    dataset: Any,
    dataset_name: str,
    dataset_role: str,
    metrics: list[Callable],
    n_threads: int,
) -> TaskEvaluationResult:
    """Run all metrics for a prompt on a dataset and return a structured result."""
    start_time = time.time()
    metric_entries = []
    for metric_fn in metrics:
        metric_name = getattr(metric_fn, "__name__", metric_fn.__class__.__name__)
        score = optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=metric_fn,
            n_threads=n_threads,
        )
        metric_entries.append(
            {
                "metric_name": metric_name,
                "score": score,
                "timestamp": time.time(),
            }
        )

    return TaskEvaluationResult(
        dataset=_dataset_metadata(dataset, dataset_name, dataset_role),
        metrics=metric_entries,  # type: ignore[arg-type]
        duration_seconds=time.time() - start_time,
    )


def _serialize_optimization_result(result: Any) -> Any:
    if hasattr(result, "model_dump"):
        return result.model_dump()
    if hasattr(result, "dict"):
        return result.dict()
    return result


def execute_task(
    *,
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    model_parameters: dict[str, Any] | None,
    test_mode: bool,
    optimizer_params_override: dict[str, Any] | None,
    optimizer_prompt_params_override: dict[str, Any] | None,
    datasets: dict[str, Any] | None = None,
    metrics: list[str | dict[str, Any]] | None = None,
    prompt_messages: list[dict[str, Any]] | None = None,
) -> TaskResult:
    """Shared execution path used by local and Modal runners."""
    timestamp_start = time.time()
    initial_prompt = None
    optimized_prompt = None
    optimize_kwargs: dict[str, Any] | None = None
    constructor_kwargs: dict[str, Any] | None = None
    test_initial_evaluation: TaskEvaluationResult | None = None
    steps: list[dict[str, Any]] = []

    with reporting_utils.suppress_opik_logs():
        try:
            if test_mode and os.getenv("OPIK_DATASET_SKIP_EXISTING") is None:
                # Avoid brittle failures in smoke runs when datasets already exist with different sizes.
                os.environ["OPIK_DATASET_SKIP_EXISTING"] = "true"

            bundle = resolve_dataset_bundle(
                dataset_name=dataset_name,
                test_mode=test_mode,
                datasets=datasets,
            )
            # Resolve dataset config defensively, tolerating base names and override names.
            _dataset_config: BenchmarkDatasetConfig | None = None
            for candidate in (
                bundle.evaluation_name,
                dataset_name,
                bundle.train_name,
                bundle.validation_name,
                bundle.test_name,
            ):
                if candidate and candidate in benchmark_config.DATASET_CONFIG:
                    _dataset_config = benchmark_config.DATASET_CONFIG[candidate]
                    break
            if _dataset_config is None:
                raise KeyError(
                    f"Dataset config not found for '{dataset_name}' "
                    f"(evaluation={bundle.evaluation_name}, train={bundle.train_name}, "
                    f"validation={bundle.validation_name}, test={bundle.test_name}). "
                    "Ensure the dataset is registered in benchmark_config.DATASET_CONFIG."
                )
            dataset_config = _dataset_config
            metrics_resolved = _resolve_metrics(
                dataset_config, cast(list[str | dict[str, Any]] | None, metrics)
            )
            if not metrics_resolved:
                raise ValueError(
                    f"No metrics configured for dataset '{dataset_config.name}'. "
                    "Provide at least one metric via dataset config or manifest overrides."
                )
            optimizer_config = benchmark_config.OPTIMIZER_CONFIGS[optimizer_name]

            constructor_kwargs = dict(optimizer_config.params)
            if optimizer_params_override:
                constructor_kwargs.update(optimizer_params_override)
            # Ensure we only supply model/model_parameters once. If provided in the config/overrides,
            # respect those; otherwise inject the runner-specified values.
            constructor_kwargs.setdefault("model", model_name)
            constructor_kwargs.setdefault("model_parameters", model_parameters)
            optimizer: BaseOptimizer = getattr(
                opik_optimizer, optimizer_config.class_name
            )(
                **constructor_kwargs,
            )

            messages = prompt_messages or _resolve_initial_prompt(bundle.train_name)
            # Bind the optimizer's model/model_parameters to the prompt so evaluations
            # use the requested model instead of ChatPrompt defaults.
            initial_prompt = ChatPrompt(
                messages=messages,  # type: ignore[arg-type]
                model=getattr(optimizer, "model", model_name),
                model_parameters=getattr(
                    optimizer, "model_parameters", model_parameters
                ),
            )

            initial_evaluation = evaluate_prompt_on_dataset(
                optimizer=optimizer,
                prompt=initial_prompt,
                dataset=bundle.evaluation,
                dataset_name=bundle.evaluation_name,
                dataset_role=bundle.evaluation_role,
                metrics=metrics_resolved,
                n_threads=4,
            )
            steps.append(
                {
                    "step_id": "initial-eval",
                    "kind": "baseline",
                    "index": 0,
                    "split": bundle.evaluation_role,
                    "prompt_snapshot": initial_prompt,
                    "metrics": {bundle.evaluation_role: initial_evaluation.metrics},
                    "llm_calls": 0,
                    "meta": {},
                }
            )

            if bundle.test is not None and bundle.test_name is not None:
                test_initial_evaluation = evaluate_prompt_on_dataset(
                    optimizer=optimizer,
                    prompt=initial_prompt,
                    dataset=bundle.test,
                    dataset_name=bundle.test_name,
                    dataset_role="test",
                    metrics=metrics_resolved,
                    n_threads=4,
                )
                steps.append(
                    {
                        "step_id": "initial-test",
                        "kind": "baseline",
                        "index": 0,
                        "split": "test",
                        "prompt_snapshot": initial_prompt,
                        "metrics": {"test": test_initial_evaluation.metrics},
                        "llm_calls": 0,
                        "meta": {},
                    }
                )

            optimize_kwargs = dict(optimizer_config.optimizer_prompt_params)
            if optimizer_prompt_params_override:
                optimize_kwargs.update(optimizer_prompt_params_override)
            optimization_results = optimizer.optimize_prompt(
                prompt=initial_prompt,
                dataset=bundle.train,
                validation_dataset=bundle.validation,
                metric=metrics_resolved[0],
                **optimize_kwargs,
            )
            optimized_prompt = ChatPrompt(
                messages=optimization_results.prompt,  # type: ignore[arg-type]
                model=getattr(optimizer, "model", model_name),
                model_parameters=getattr(
                    optimizer, "model_parameters", model_parameters
                ),
            )

            optimized_evaluation = evaluate_prompt_on_dataset(
                optimizer=optimizer,
                prompt=optimized_prompt,
                dataset=bundle.evaluation,
                dataset_name=bundle.evaluation_name,
                dataset_role=bundle.evaluation_role,
                metrics=metrics_resolved,
                n_threads=4,
            )
            steps.append(
                {
                    "step_id": "final-eval",
                    "kind": "post_opt",
                    "index": 1,
                    "split": bundle.evaluation_role,
                    "prompt_snapshot": optimized_prompt,
                    "metrics": {bundle.evaluation_role: optimized_evaluation.metrics},
                    "llm_calls": optimization_results.llm_calls,
                    "meta": {},
                }
            )

            test_evaluation = None
            if bundle.test is not None and bundle.test_name is not None:
                test_evaluation = evaluate_prompt_on_dataset(
                    optimizer=optimizer,
                    prompt=optimized_prompt,
                    dataset=bundle.test,
                    dataset_name=bundle.test_name,
                    dataset_role="test",
                    metrics=metrics_resolved,
                    n_threads=4,
                )
                steps.append(
                    {
                        "step_id": "final-test",
                        "kind": "post_opt",
                        "index": 1,
                        "split": "test",
                        "prompt_snapshot": optimized_prompt,
                        "metrics": {"test": test_evaluation.metrics},
                        "llm_calls": 0,
                        "meta": {},
                    }
                )

            evaluations = {
                "initial": EvaluationSet(
                    **{
                        bundle.evaluation_role: EvaluationSet.EvaluationEntry(
                            step_id="initial-eval", result=initial_evaluation
                        ),
                        "test": EvaluationSet.EvaluationEntry(
                            step_id="initial-test", result=test_initial_evaluation
                        )
                        if test_initial_evaluation
                        else None,
                    }
                ),
                "final": EvaluationSet(
                    **{
                        bundle.evaluation_role: EvaluationSet.EvaluationEntry(
                            step_id="final-eval", result=optimized_evaluation
                        ),
                        "test": EvaluationSet.EvaluationEntry(
                            step_id="final-test", result=test_evaluation
                        )
                        if test_evaluation
                        else None,
                    }
                ),
            }

            stages: list[EvaluationStage] = []
            stages.append(
                EvaluationStage(
                    stage="initial",
                    split=bundle.evaluation_role,
                    evaluation=initial_evaluation,
                    prompt_snapshot=initial_prompt,
                    step_ref="initial-eval",
                )
            )
            if test_initial_evaluation:
                stages.append(
                    EvaluationStage(
                        stage="initial",
                        split="test",
                        evaluation=test_initial_evaluation,
                        prompt_snapshot=initial_prompt,
                        step_ref="initial-test",
                    )
                )
            stages.append(
                EvaluationStage(
                    stage="final",
                    split=bundle.evaluation_role,
                    evaluation=optimized_evaluation,
                    prompt_snapshot=optimized_prompt,
                    step_ref="final-eval",
                )
            )
            if test_evaluation:
                stages.append(
                    EvaluationStage(
                        stage="final",
                        split="test",
                        evaluation=test_evaluation,
                        prompt_snapshot=optimized_prompt,
                        step_ref="final-test",
                    )
                )

            return TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status=TASK_STATUS_SUCCESS,
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                optimized_prompt=optimized_prompt,
                evaluations=evaluations,
                stages=stages,
                optimization_history={"steps": steps},
                error_message=None,
                llm_calls_total_optimization=optimization_results.llm_calls,
                optimization_raw_result=optimization_results,
                optimization_summary=_serialize_optimization_result(
                    optimization_results
                ),
                timestamp_end=time.time(),
                dataset_metadata=collect_dataset_metadata(bundle),
                evaluation_split=bundle.evaluation_role,
                requested_split=bundle.requested_split,
                optimizer_prompt_params_used=optimize_kwargs,
                optimizer_params_used=constructor_kwargs,
            )
        except Exception:
            return TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status=TASK_STATUS_FAILED,
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                optimized_prompt=optimized_prompt,
                error_message=traceback.format_exc(),
                timestamp_end=time.time(),
                dataset_metadata={},
                evaluation_split=None,
                requested_split=None,
                evaluations={},
                stages=[],
                optimization_history={"steps": steps},
                optimizer_prompt_params_used=optimize_kwargs,
                optimizer_params_used=constructor_kwargs,
            )
