"""Shared helpers for running benchmark tasks locally or on Modal."""

from __future__ import annotations

import time
import traceback
from dataclasses import dataclass
from typing import Any
from collections.abc import Callable

import benchmark_config
import opik_optimizer.datasets
from benchmark_task import (
    DatasetMetadata,
    TaskEvaluationResult,
    TaskResult,
    TASK_STATUS_FAILED,
    TASK_STATUS_SUCCESS,
)
from opik_optimizer import BaseOptimizer, ChatPrompt, reporting_utils


_SPLIT_SUFFIXES = {
    "train": "_train",
    "validation": "_validation",
    "test": "_test",
}


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


def _load_dataset(dataset_name: str, split: str | None, test_mode: bool) -> Any:
    """Load a dataset by name, falling back to base loader when split-specific helpers are absent."""
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
        return base_loader(**kwargs)

    raise ValueError(f"Unknown dataset loader for '{dataset_name}'.")


def resolve_dataset_bundle(
    dataset_name: str,
    test_mode: bool,
    dataset_overrides: dict[str, Any] | None = None,
) -> DatasetBundle:
    """Return train/validation/test dataset objects for a given benchmark dataset key.

    When ``dataset_overrides`` is provided, the loader specs (train/validation/test)
    are used instead of the registered preset slices. If only one override is
    given, it is reused for all splits.
    """
    if dataset_overrides:
        role_specs = (
            dataset_overrides
            if any(role in dataset_overrides for role in ("train", "validation", "test"))
            else {
                "train": dataset_overrides,
                "validation": dataset_overrides,
                "test": dataset_overrides,
            }
        )

        def _load_override(role: str) -> tuple[str, Any] | tuple[None, None]:
            spec = role_specs.get(role)
            if spec is None:
                return None, None
            loader_name = spec.get("loader") or dataset_name
            kwargs = {k: v for k, v in spec.items() if k != "loader"}
            kwargs.setdefault("dataset_name", f"{loader_name}_{role}")
            kwargs["test_mode"] = test_mode
            loader = getattr(opik_optimizer.datasets, loader_name, None)
            if callable(loader):
                return kwargs["dataset_name"], loader(**kwargs)
            raise ValueError(f"Unknown dataset loader '{loader_name}' for role '{role}'.")

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

    train_dataset = _load_dataset(train_name, "train", test_mode=test_mode)
    validation_dataset = (
        _load_dataset(validation_name, "validation", test_mode=test_mode)
        if validation_name
        else None
    )
    test_dataset = (
        _load_dataset(test_name, "test", test_mode=test_mode) if test_name else None
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


def _dataset_metadata(dataset: Any, dataset_name: str, role: str) -> DatasetMetadata:
    return DatasetMetadata(
        name=getattr(dataset, "name", dataset_name),
        id=getattr(dataset, "id", None),
        split=role,
    )


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
    dataset_overrides: dict[str, Any] | None = None,
) -> TaskResult:
    """Shared execution path used by local and Modal runners."""
    timestamp_start = time.time()
    initial_prompt = None
    optimized_prompt = None

    with reporting_utils.suppress_opik_logs():
        try:
            bundle = resolve_dataset_bundle(
                dataset_name=dataset_name,
                test_mode=test_mode,
                dataset_overrides=dataset_overrides,
            )
            dataset_config = benchmark_config.DATASET_CONFIG.get(
                bundle.evaluation_name, benchmark_config.DATASET_CONFIG[dataset_name]
            )
            optimizer_config = benchmark_config.OPTIMIZER_CONFIGS[optimizer_name]

            constructor_kwargs = dict(optimizer_config.params)
            if optimizer_params_override:
                constructor_kwargs.update(optimizer_params_override)
            optimizer: BaseOptimizer = getattr(
                opik_optimizer, optimizer_config.class_name
            )(
                model=model_name,
                model_parameters=model_parameters,
                **constructor_kwargs,
            )

            messages = benchmark_config.INITIAL_PROMPTS[bundle.train_name]
            initial_prompt = ChatPrompt(messages=messages)  # type: ignore[arg-type]

            initial_evaluation = evaluate_prompt_on_dataset(
                optimizer=optimizer,
                prompt=initial_prompt,
                dataset=bundle.evaluation,
                dataset_name=bundle.evaluation_name,
                dataset_role=bundle.evaluation_role,
                metrics=dataset_config.metrics,
                n_threads=4,
            )

            optimize_kwargs = dict(optimizer_config.optimizer_prompt_params)
            if optimizer_prompt_params_override:
                optimize_kwargs.update(optimizer_prompt_params_override)
            optimization_results = optimizer.optimize_prompt(
                prompt=initial_prompt,
                dataset=bundle.train,
                validation_dataset=bundle.validation,
                metric=dataset_config.metrics[0],
                **optimize_kwargs,
            )
            optimized_prompt = ChatPrompt(messages=optimization_results.prompt)

            optimized_evaluation = evaluate_prompt_on_dataset(
                optimizer=optimizer,
                prompt=optimized_prompt,
                dataset=bundle.evaluation,
                dataset_name=bundle.evaluation_name,
                dataset_role=bundle.evaluation_role,
                metrics=dataset_config.metrics,
                n_threads=4,
            )

            test_evaluation = None
            if bundle.test is not None and bundle.test_name is not None:
                test_evaluation = evaluate_prompt_on_dataset(
                    optimizer=optimizer,
                    prompt=optimized_prompt,
                    dataset=bundle.test,
                    dataset_name=bundle.test_name,
                    dataset_role="test",
                    metrics=dataset_config.metrics,
                    n_threads=4,
                )

            return TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status=TASK_STATUS_SUCCESS,
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                initial_evaluation=initial_evaluation,
                optimized_prompt=optimized_prompt,
                optimized_evaluation=optimized_evaluation,
                test_evaluation=test_evaluation,
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
                optimize_params_used=None,
                optimizer_params_used=None,
            )
