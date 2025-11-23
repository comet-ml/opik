from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field, ValidationError

from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec


class ManifestTask(BaseModel):
    dataset: str | dict[str, Any]
    optimizer: str
    model: str
    test_mode: bool | None = None
    model_parameters: dict[str, Any] | None = None
    optimizer_params: dict[str, Any] | None = None
    optimizer_prompt_params: dict[str, Any] | None = None
    datasets: dict[str, Any] | None = None
    metrics: list[str | dict[str, Any]] | None = None
    prompt: list[dict[str, Any]] | None = None


class GeneratorDataset(BaseModel):
    dataset: str | dict[str, Any]
    datasets: dict[str, Any] | None = None


class GeneratorModel(BaseModel):
    name: str
    model_parameters: dict[str, Any] | None = None


class GeneratorOptimizer(BaseModel):
    name: str
    optimizer_params: dict[str, Any] | None = None
    optimizer_prompt_params: dict[str, Any] | None = None


class GeneratorSpec(BaseModel):
    datasets: list[GeneratorDataset]
    models: list[GeneratorModel]
    optimizers: list[GeneratorOptimizer]
    metrics: list[str | dict[str, Any]] | None = None
    test_mode: bool | None = None
    prompt: list[dict[str, Any]] | None = None


class BenchmarkManifest(BaseModel):
    seed: int | None = None
    test_mode: bool | None = None
    tasks: list[ManifestTask] = Field(default_factory=list)
    generators: list[GeneratorSpec] = Field(default_factory=list)


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
) -> list[BenchmarkTaskSpec]:
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
        metrics: list[str | dict[str, Any]] | None,
        prompt: list[dict[str, Any]] | None,
    ) -> None:
        dataset_name, datasets_override = _normalize_dataset_entry(dataset, datasets)
        specs.append(
            BenchmarkTaskSpec(
                dataset_name=dataset_name,
                optimizer_name=optimizer,
                model_name=model,
                test_mode=test_mode if test_mode is not None else default_test_mode,
                model_parameters=model_parameters,
                optimizer_params=optimizer_params,
                optimizer_prompt_params=optimizer_prompt_params,
                datasets=datasets_override,
                metrics=metrics,
                prompt_messages=prompt,
            )
        )

    specs: list[BenchmarkTaskSpec] = []
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
