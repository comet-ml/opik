from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field, ValidationError

from benchmark_taskspec import BenchmarkTaskSpec


class ManifestTask(BaseModel):
    dataset: str
    optimizer: str
    model: str
    test_mode: bool | None = None
    model_parameters: dict[str, Any] | None = None
    optimizer_params: dict[str, Any] | None = None
    optimizer_prompt_params: dict[str, Any] | None = None
    dataset_overrides: dict[str, Any] | None = None


class BenchmarkManifest(BaseModel):
    seed: int | None = None
    test_mode: bool | None = None
    tasks: list[ManifestTask] = Field(default_factory=list)


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
    specs: list[BenchmarkTaskSpec] = []
    default_test_mode = (
        manifest.test_mode if manifest.test_mode is not None else fallback_test_mode
    )
    for task in manifest.tasks:
        specs.append(
            BenchmarkTaskSpec(
                dataset_name=task.dataset,
                optimizer_name=task.optimizer,
                model_name=task.model,
                test_mode=(
                    task.test_mode if task.test_mode is not None else default_test_mode
                ),
                model_parameters=task.model_parameters,
                optimizer_params=task.optimizer_params,
                optimizer_prompt_params=task.optimizer_prompt_params,
                dataset_overrides=task.dataset_overrides,
            )
        )
    return specs
