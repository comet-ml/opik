"""
Modal worker function for running opik_optimizer benchmarks.

This module contains the deployable Modal function that runs individual benchmark tasks.
Deploy this with: modal deploy benchmarks/benchmark_worker.py

Once deployed, the worker can be triggered by run_benchmark.py --modal without requiring
your local machine to stay running.
"""

import os
import sys
from typing import Any

import modal
from benchmarks.benchmark_constants import (
    WORKER_TIMEOUT_SECONDS,
    MODAL_SECRET_NAME,
)

# Define Modal app
app = modal.App("opik-optimizer-benchmarks")

# Create Modal image with all required dependencies
# Note: opik_optimizer will automatically install all its dependencies
# (opik, datasets, litellm, etc.) as declared in pyproject.toml
image = (
    modal.Image.debian_slim(python_version="3.12")
    .add_local_dir(
        local_path=os.path.abspath(
            os.path.join(os.path.dirname(__file__), os.pardir, os.pardir)
        ),
        remote_path="/root/opik_optimizer_repo",
        ignore=[
            ".venv",
            ".git",
            "__pycache__",
            "benchmark_results",
            "build",
            "dist",
            "node_modules",
        ],
        copy=True,
    )
    .pip_install("/root/opik_optimizer_repo")
    # Add benchmarks directory for configs
    .add_local_dir(
        local_path=os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir)),
        remote_path="/root/benchmarks",
        ignore=["__pycache__", ".venv", "benchmark_results"],
        copy=True,
    )
)


# Create Modal volume for persistent result storage
# Results persist indefinitely, beyond Modal's 7-day FunctionCall limit
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)

# Environment secrets - configure these in Modal dashboard
# Required: OPIK_API_KEY (and model API keys you plan to use)
# Create with e.g.:
#   modal secret create opik-benchmarks OPIK_API_KEY="$OPIK_API_KEY" OPENAI_API_KEY="$OPENAI_API_KEY"
modal_secrets = [
    modal.Secret.from_name(MODAL_SECRET_NAME),
]


@app.function(
    image=image,
    volumes={"/results": results_volume},
    secrets=modal_secrets,
    timeout=WORKER_TIMEOUT_SECONDS,
    retries=modal.Retries(
        max_retries=2,
        initial_delay=10.0,
        backoff_coefficient=2.0,
    ),
    cpu=2.0,
    memory=4096,  # 4GB RAM
    # Concurrency control: max_containers limits how many tasks run concurrently
    # This can be overridden dynamically when looking up the deployed function
)
def run_optimization_modal(
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    model_parameters: dict | None,
    test_mode: bool,
    run_id: str,
    optimizer_params: dict | None = None,
    optimizer_prompt_params: dict | None = None,
    datasets: dict | None = None,
    metrics: list[str | dict[str, Any]] | None = None,
    prompt_messages: list[dict[str, Any]] | None = None,
) -> dict:
    """
    Run a single optimization task on Modal.

    This function runs completely in the cloud and saves results to a Modal Volume.
    Results persist indefinitely and can be retrieved later by check_results.py.

    Args:
        task_id: Unique identifier for this task
        dataset_name: Name of dataset to use
        optimizer_name: Name of optimizer to use
        model_name: Name of model to use
        test_mode: Whether to run in test mode (5 examples)
        run_id: Unique identifier for this benchmark run
        optimizer_params: Optional overrides merged into the optimizer constructor
        optimizer_prompt_params: Optional overrides merged into optimize_prompt

    Returns:
        Dictionary containing task result (also saved to Volume)
    """
    # Add benchmarks directory to Python path
    sys.path.insert(0, "/root/benchmarks")

    # Import core logic modules
    import time
    from benchmarks.core.benchmark_task import TaskResult, TASK_STATUS_RUNNING
    from benchmarks.modal_utils.worker_core import run_optimization_task
    from benchmarks.modal_utils.storage import save_result_to_volume

    # Save "Running" status at the start (before any long-running work)
    timestamp_start = time.time()
    running_result = TaskResult(
        id=task_id,
        dataset_name=dataset_name,
        optimizer_name=optimizer_name,
        model_name=model_name,
        model_parameters=model_parameters,
        status=TASK_STATUS_RUNNING,
        timestamp_start=timestamp_start,
    )
    # Save immediately so it's visible to check_results.py
    print(f"[{task_id}] Saving Running status to volume...")
    save_result_to_volume(running_result, run_id, results_volume)
    print(f"[{task_id}] Running status saved, starting optimization...")

    # Run the optimization task (it will set its own timestamp_start, but we'll preserve ours)
    result = run_optimization_task(
        task_id=task_id,
        dataset_name=dataset_name,
        optimizer_name=optimizer_name,
        model_name=model_name,
        model_parameters=model_parameters,
        test_mode=test_mode,
        optimizer_params_override=optimizer_params,
        optimizer_prompt_params_override=optimizer_prompt_params,
        datasets=datasets,
        metrics=metrics,
        prompt_messages=prompt_messages,
    )

    # Ensure the final result uses the same timestamp_start as the Running status
    result.timestamp_start = timestamp_start

    # Save final result to Modal Volume (overwrites the Running status)
    result_dict = save_result_to_volume(result, run_id, results_volume)

    return result_dict
