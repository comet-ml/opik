"""Core optimization logic for Modal workers (without Modal decorators)."""

import time
import traceback
from typing import Any

from benchmark_task import TaskResult, TASK_STATUS_FAILED
from utils.task_runner import execute_task


def run_optimization_task(
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    model_parameters: dict[str, Any] | None,
    test_mode: bool,
    optimizer_params_override: dict[str, Any] | None = None,
    optimizer_prompt_params_override: dict[str, Any] | None = None,
) -> TaskResult:
    """
    Run a single optimization task on Modal infrastructure.

    Mirrors `local.runner.run_optimization` but omits Live console handling so
    Modal workers can focus purely on running the benchmark. Two optional
    override dictionaries allow per-task customization:

      * ``optimizer_params_override`` – merged into the optimizer constructor
        kwargs (e.g., to tweak seeds or thread counts).
      * ``optimizer_prompt_params_override`` – merged into the optimizer's
        ``optimize_prompt`` call (typically used to enforce rollout budgets
        derived at submission time).
    Args:
        task_id: Unique identifier for this task
        dataset_name: Name of dataset to use
        optimizer_name: Name of optimizer to use
        model_name: Name of model to use
        test_mode: Whether to run in test mode (5 examples)
        optimizer_params_override: Constructor kwargs override for the optimizer
        optimizer_prompt_params_override: Additional kwargs merged into
            ``optimize_prompt`` (usually rollout caps or prompt-iteration knobs).

    Returns:
        TaskResult object containing the optimization results
    """
    timestamp_start = time.time()
    print(f"[{task_id}] Starting optimization...")
    try:
        result = execute_task(
            task_id=task_id,
            dataset_name=dataset_name,
            optimizer_name=optimizer_name,
            model_name=model_name,
            model_parameters=model_parameters,
            test_mode=test_mode,
            optimizer_params_override=optimizer_params_override,
            optimizer_prompt_params_override=optimizer_prompt_params_override,
        )
        result.timestamp_start = timestamp_start
        print(
            f"[{task_id}] Completed successfully in {time.time() - timestamp_start:.2f}s"
        )
        return result
    except Exception as e:
        print(f"[{task_id}] Failed with error: {str(e)}")
        return TaskResult(
            id=task_id,
            dataset_name=dataset_name,
            optimizer_name=optimizer_name,
            model_name=model_name,
            status=TASK_STATUS_FAILED,
            timestamp_start=timestamp_start,
            error_message=traceback.format_exc(),
            timestamp_end=time.time(),
        )
