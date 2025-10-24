"""
Modal worker function for running opik_optimizer benchmarks.

This module contains the deployable Modal function that runs individual benchmark tasks.
Deploy this with: modal deploy benchmarks/benchmark_worker.py

Once deployed, the worker can be triggered by submit_benchmarks.py without requiring
your local machine to stay running.
"""

import os
import sys

import modal

# Define Modal app
app = modal.App("opik-optimizer-benchmarks")

# Create Modal image with all required dependencies
# Note: opik_optimizer will automatically install all its dependencies
# (opik, datasets, litellm, etc.) as declared in pyproject.toml
image = (
    modal.Image.debian_slim(python_version="3.12")
    .pip_install("opik_optimizer>=2.1.3")
    # Add local benchmarks directory so Modal can access config files
    .add_local_dir(
        local_path=os.path.dirname(os.path.abspath(__file__)),
        remote_path="/root/benchmarks",
        ignore=["__pycache__", ".venv", "benchmark_results"],
    )
)

# Create Modal volume for persistent result storage
# Results persist indefinitely, beyond Modal's 7-day FunctionCall limit
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)

# Environment secrets - configure these in Modal dashboard
# Required: OPIK_API_KEY, OPIK_WORKSPACE
# Optional: OPENAI_API_KEY, ANTHROPIC_API_KEY, etc. depending on models used
modal_secrets = [
    modal.Secret.from_name("opik-credentials"),
    modal.Secret.from_name("llm-api-keys"),
]


@app.function(
    image=image,
    volumes={"/results": results_volume},
    secrets=modal_secrets,
    timeout=3600,  # 1 hour timeout per task
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
    test_mode: bool,
    run_id: str,
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

    Returns:
        Dictionary containing task result (also saved to Volume)
    """
    # Add benchmarks directory to Python path
    sys.path.insert(0, "/root/benchmarks")

    # Import core logic modules
    from modal_utils.worker_core import run_optimization_task
    from modal_utils.storage import save_result_to_volume

    # Run the optimization task
    result = run_optimization_task(
        task_id=task_id,
        dataset_name=dataset_name,
        optimizer_name=optimizer_name,
        model_name=model_name,
        test_mode=test_mode,
    )

    # Save result to Modal Volume and return
    result_dict = save_result_to_volume(result, run_id, results_volume)

    return result_dict
