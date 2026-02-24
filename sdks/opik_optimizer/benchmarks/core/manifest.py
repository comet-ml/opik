from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from benchmarks.core.types import TaskSpec


class PromptMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")

    role: str
    content: str


class MetricSpec(BaseModel):
    model_config = ConfigDict(extra="ignore")

    path: str
    args: list[Any] | None = None
    kwargs: dict[str, Any] | None = None


class ManifestTask(BaseModel):
    model_config = ConfigDict(extra="ignore")

    dataset: str | dict[str, Any]
    optimizer: str
    model: str
    test_mode: bool | None = None
    model_parameters: dict[str, Any] | None = None
    optimizer_params: dict[str, Any] | None = None
    optimizer_prompt_params: dict[str, Any] | None = None
    datasets: dict[str, Any] | None = None
    metrics: list[str | MetricSpec] | None = None
    prompt: list[PromptMessage] | None = None

    @model_validator(mode="after")
    def _validate_datasets(self) -> ManifestTask:
        if self.datasets and "train" not in self.datasets:
            raise ValueError(
                "datasets config must include a train split when provided."
            )
        return self


class GeneratorDataset(BaseModel):
    model_config = ConfigDict(extra="ignore")

    dataset: str | dict[str, Any]
    datasets: dict[str, Any] | None = None

    @model_validator(mode="after")
    def _validate_datasets(self) -> GeneratorDataset:
        if self.datasets and "train" not in self.datasets:
            raise ValueError(
                "generator datasets config must include a train split when provided."
            )
        return self


class GeneratorModel(BaseModel):
    model_config = ConfigDict(extra="ignore")

    name: str
    model_parameters: dict[str, Any] | None = None


class GeneratorOptimizer(BaseModel):
    model_config = ConfigDict(extra="ignore")

    name: str
    optimizer_params: dict[str, Any] | None = None
    optimizer_prompt_params: dict[str, Any] | None = None


class GeneratorSpec(BaseModel):
    model_config = ConfigDict(extra="ignore")

    datasets: list[GeneratorDataset]
    models: list[GeneratorModel]
    optimizers: list[GeneratorOptimizer]
    metrics: list[str | MetricSpec] | None = None
    test_mode: bool | None = None
    prompt: list[PromptMessage] | None = None


class BenchmarkManifest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    seed: int | None = None
    test_mode: bool | None = None
    tasks: list[ManifestTask] = Field(default_factory=list)
    generators: list[GeneratorSpec] = Field(default_factory=list)

    @model_validator(mode="after")
    def _validate_has_tasks_or_generators(self) -> BenchmarkManifest:
        """Ensure the manifest defines at least one task or generator.

        Raises:
            ValueError: If both ``tasks`` and ``generators`` are empty.
        """
        if not self.tasks and not self.generators:
            raise ValueError("Manifest must include at least one task or generator.")
        return self


def _normalize_metrics(
    metrics: list[str | MetricSpec] | None,
) -> list[str | dict[str, Any]] | None:
    if metrics is None:
        return None

    normalized: list[str | dict[str, Any]] = []
    for metric in metrics:
        if isinstance(metric, str):
            normalized.append(metric)
        else:
            payload: dict[str, Any] = {"path": metric.path}
            if metric.args is not None:
                payload["args"] = metric.args
            if metric.kwargs is not None:
                payload["kwargs"] = metric.kwargs
            normalized.append(payload)
    return normalized


def _normalize_prompt(
    prompt: list[PromptMessage] | None,
) -> list[dict[str, Any]] | None:
    if prompt is None:
        return None
    return [entry.model_dump() for entry in prompt]


def load_manifest(path: str) -> BenchmarkManifest:
    manifest_path = Path(path)
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest file not found: {manifest_path}")

    data: dict[str, Any] = json.loads(manifest_path.read_text())
    try:
        return BenchmarkManifest.model_validate(data)
    except ValidationError as exc:
        raise ValueError(f"Invalid manifest: {exc}") from exc


def manifest_to_task_specs(
    manifest: BenchmarkManifest, fallback_test_mode: bool = False
) -> list[TaskSpec]:
    def _normalize_dataset_entry(
        dataset_field: str | dict[str, Any], datasets_field: dict[str, Any] | None
    ) -> tuple[str, dict[str, Any] | None]:
        datasets_override = datasets_field.copy() if datasets_field else None

        if isinstance(dataset_field, dict):
            loader_name = dataset_field.get("loader")
            if not loader_name:
                raise ValueError(
                    "Dataset override objects must include a 'loader' key."
                )
            dataset_name: str = str(dataset_field.get("dataset_name", loader_name))
            if datasets_override is None:
                # If the user provided a single dataset override without explicit splits,
                # apply it to train only. Validation/test must be requested explicitly.
                datasets_override = {"train": dict(dataset_field)}
        else:
            dataset_name = dataset_field

        return dataset_name, datasets_override

    def _add_task(
        *,
        dataset: str | dict[str, Any],
        datasets: dict[str, Any] | None,
        optimizer: str,
        model: str,
        test_mode: bool | None,
        model_parameters: dict[str, Any] | None,
        optimizer_params: dict[str, Any] | None,
        optimizer_prompt_params: dict[str, Any] | None,
        metrics: list[str | MetricSpec] | None,
        prompt: list[PromptMessage] | None,
    ) -> None:
        dataset_name, datasets_override = _normalize_dataset_entry(dataset, datasets)
        specs.append(
            TaskSpec(
                dataset_name=dataset_name,
                optimizer_name=optimizer,
                model_name=model,
                test_mode=test_mode if test_mode is not None else default_test_mode,
                model_parameters=model_parameters,
                optimizer_params=optimizer_params,
                optimizer_prompt_params=optimizer_prompt_params,
                datasets=datasets_override,
                metrics=_normalize_metrics(metrics),
                prompt_messages=_normalize_prompt(prompt),
            )
        )

    specs: list[TaskSpec] = []
    default_test_mode = (
        manifest.test_mode if manifest.test_mode is not None else fallback_test_mode
    )
    for task in manifest.tasks:
        _add_task(
            dataset=task.dataset,
            datasets=task.datasets,
            optimizer=task.optimizer,
            model=task.model,
            test_mode=task.test_mode,
            model_parameters=task.model_parameters,
            optimizer_params=task.optimizer_params,
            optimizer_prompt_params=task.optimizer_prompt_params,
            metrics=task.metrics,
            prompt=task.prompt,
        )

    for generator in manifest.generators:
        gen_test_mode = (
            generator.test_mode
            if generator.test_mode is not None
            else default_test_mode
        )
        for dataset_entry in generator.datasets:
            for model_entry in generator.models:
                for opt_entry in generator.optimizers:
                    _add_task(
                        dataset=dataset_entry.dataset,
                        datasets=dataset_entry.datasets,
                        optimizer=opt_entry.name,
                        model=model_entry.name,
                        test_mode=gen_test_mode,
                        model_parameters=model_entry.model_parameters,
                        optimizer_params=opt_entry.optimizer_params,
                        optimizer_prompt_params=opt_entry.optimizer_prompt_params,
                        metrics=generator.metrics,
                        prompt=generator.prompt,
                    )

    return specs
