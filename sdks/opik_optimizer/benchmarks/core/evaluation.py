from __future__ import annotations

from typing import Any

from benchmarks.core.types import TaskResult
from benchmarks.utils.task_runner import execute_task


def run_task_evaluation(
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
    """Shared benchmark evaluation entrypoint used by engine workers."""
    # TODO(benchmarks): this thin wrapper can be removed once engines call
    # task_runner.execute_task directly through a stabilized runtime interface.
    return execute_task(
        task_id=task_id,
        dataset_name=dataset_name,
        optimizer_name=optimizer_name,
        model_name=model_name,
        model_parameters=model_parameters,
        test_mode=test_mode,
        optimizer_params_override=optimizer_params_override,
        optimizer_prompt_params_override=optimizer_prompt_params_override,
        datasets=datasets,
        metrics=metrics,
        prompt_messages=prompt_messages,
    )
