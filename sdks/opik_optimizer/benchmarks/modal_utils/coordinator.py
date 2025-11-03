"""Modal task coordination logic (pure functions)."""

import tempfile
from typing import Any


def generate_task_combinations(
    demo_datasets: list[str],
    optimizers: list[str],
    models: list[str],
    test_mode: bool,
    run_id: str,
    completed_tasks: set[str] | None = None,
    failed_tasks: set[str] | None = None,
    retry_failed: bool = False,
    resume: bool = False,
) -> tuple[list[dict], int]:
    """
    Generate all task combinations to submit.

    Args:
        demo_datasets: List of dataset names
        optimizers: List of optimizer names
        models: List of model names
        test_mode: Whether to run in test mode
        run_id: Unique run identifier
        completed_tasks: Set of already completed task IDs
        failed_tasks: Set of failed task IDs
        retry_failed: Whether to retry only failed tasks
        resume: Whether to resume (skip completed tasks)

    Returns:
        Tuple of (task_list, skipped_count)
    """
    if completed_tasks is None:
        completed_tasks = set()
    if failed_tasks is None:
        failed_tasks = set()

    all_tasks = []
    skipped_count = 0

    for dataset_name in demo_datasets:
        for optimizer_name in optimizers:
            for model_name in models:
                task_id = f"{dataset_name}_{optimizer_name}_{model_name}"

                # Skip logic for resume/retry
                if retry_failed and task_id not in failed_tasks:
                    skipped_count += 1
                    continue

                if resume and task_id in completed_tasks:
                    skipped_count += 1
                    continue

                all_tasks.append(
                    {
                        "task_id": task_id,
                        "dataset_name": dataset_name,
                        "optimizer_name": optimizer_name,
                        "model_name": model_name,
                        "test_mode": test_mode,
                        "run_id": run_id,
                    }
                )

    return all_tasks, skipped_count


def submit_tasks_to_worker(
    worker: Any, tasks: list[dict], max_concurrent: int
) -> list[dict]:
    """
    Submit tasks to Modal worker and collect function call IDs.

    Args:
        worker: Modal Function object
        tasks: List of task parameter dictionaries
        max_concurrent: Maximum concurrent tasks (for logging only)

    Returns:
        List of function call ID dictionaries
    """
    function_call_ids = []
    total_tasks = len(tasks)

    for i, task_params in enumerate(tasks, 1):
        try:
            call = worker.spawn(**task_params)
            function_call_ids.append(
                {
                    "task_id": task_params["task_id"],
                    "call_id": call.object_id,
                    "status": "submitted",
                }
            )
            if i % 10 == 0 or i == total_tasks:
                print(f"   Submitted {i}/{total_tasks} tasks...")
        except Exception as e:
            print(f"   ⚠️  Failed to submit task {task_params['task_id']}: {e}")
            function_call_ids.append(
                {
                    "task_id": task_params["task_id"],
                    "call_id": None,
                    "status": "failed_to_submit",
                    "error": str(e),
                }
            )

    return function_call_ids


def load_previous_run_metadata(run_id: str) -> tuple[set[str], set[str]]:
    """
    Load metadata from previous run.

    Note: This is a limitation of Modal's local entrypoint environment.
    The volume needs to be used within a function context to download.
    For now, we return empty sets.

    Args:
        run_id: Run ID to load metadata for

    Returns:
        Tuple of (completed_task_ids, failed_task_ids)
    """
    completed_tasks: set[str] = set()
    failed_tasks: set[str] = set()

    # Download the run's results directory to temp location
    with tempfile.TemporaryDirectory() as _tmpdir:
        try:
            # The volume needs to be used within a function context to download
            # This is a limitation of Modal's local entrypoint environment

            print("   ⚠️  Note: Cannot load previous results in local entrypoint mode")
            print("      All tasks will be submitted (duplicates may occur)")

        except Exception as e:
            print(f"   ⚠️  Could not load previous run: {e}")

    return completed_tasks, failed_tasks
