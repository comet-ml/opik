from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

from benchmarks.core.benchmark_task import TaskResult
from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec


class PreflightContext(BaseModel):
    system_time: str
    cwd: str
    manifest_path: str | None = None
    checkpoint_dir: str | None = None
    run_id: str | None = None
    opik_version: str | None = None
    opik_optimizer_version: str | None = None


class PreflightEntry(BaseModel):
    task_id: str
    short_id: str
    dataset_name: str
    evaluation_name: str | None
    optimizer_name: str
    model_name: str
    status: Literal["ok", "error"]
    splits: str | None = None
    error: str | None = None


class PreflightSummary(BaseModel):
    total_tasks: int
    datasets: list[str]
    optimizers: list[str]
    models: list[str]
    errors: list[str] = []


class PreflightReport(BaseModel):
    context: PreflightContext
    summary: PreflightSummary
    entries: list[PreflightEntry]


class BenchmarkRunResult(BaseModel):
    run_id: str
    test_mode: bool
    preflight: PreflightReport | None = None
    tasks: list[BenchmarkTaskSpec]
    task_results: list[TaskResult]
    checkpoint_path: str
    results_path: str | None = None
