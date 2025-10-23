"""
Modal worker function for running opik_optimizer benchmarks.

This module contains the deployable Modal function that runs individual benchmark tasks.
Deploy this with: modal deploy benchmarks/benchmark_worker.py

Once deployed, the worker can be triggered by submit_benchmarks.py without requiring
your local machine to stay running.
"""

import json
import os
import sys
import time
import traceback
from pathlib import Path
from typing import TYPE_CHECKING, Any

import modal

if TYPE_CHECKING:
    from benchmark_task import TaskResult

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

    import benchmark_config
    import opik_optimizer
    import opik_optimizer.datasets
    from benchmark_task import TaskEvaluationResult, TaskResult
    from opik_optimizer import BaseOptimizer, reporting_utils
    from opik_optimizer.optimization_config import chat_prompt

    timestamp_start = time.time()
    initial_prompt = None
    optimized_prompt = None

    print(f"[{task_id}] Starting optimization...")

    with reporting_utils.suppress_opik_logs():
        try:
            # Initialize the dataset, optimizer, metrics and initial_prompt
            dataset_config = benchmark_config.DATASET_CONFIG[dataset_name]
            dataset = getattr(opik_optimizer.datasets, dataset_name)(
                test_mode=test_mode
            )

            optimizer_config = benchmark_config.OPTIMIZER_CONFIGS[optimizer_name]
            optimizer: BaseOptimizer = getattr(
                opik_optimizer, optimizer_config.class_name
            )(model=model_name, **optimizer_config.params)

            messages = benchmark_config.INITIAL_PROMPTS[dataset_name]
            initial_prompt = chat_prompt.ChatPrompt(messages=messages)  # type: ignore

            # Run initial evaluation
            print(f"[{task_id}] Running initial evaluation...")
            start_time_initial_eval = time.time()
            initial_evaluation = []
            for metric_ in dataset_config.metrics:
                result = optimizer.evaluate_prompt(
                    prompt=initial_prompt, dataset=dataset, metric=metric_, n_threads=4
                )
                initial_evaluation.append(
                    {
                        "metric_name": metric_.__name__,
                        "score": result,
                        "timestamp": time.time(),
                    }
                )
            initial_evaluation_duration = time.time() - start_time_initial_eval
            print(
                f"[{task_id}] Initial evaluation complete in {initial_evaluation_duration:.2f}s"
            )

            # Run optimization
            print(f"[{task_id}] Running optimization...")
            optimization_results = optimizer.optimize_prompt(
                prompt=initial_prompt,
                dataset=dataset,
                metric=dataset_config.metrics[0],
                **optimizer_config.optimize_params,
            )
            optimized_prompt = chat_prompt.ChatPrompt(
                messages=optimization_results.prompt
            )

            # Run final evaluation
            print(f"[{task_id}] Running final evaluation...")
            start_time_final_eval = time.time()
            optimized_evaluation = []
            for metric_ in dataset_config.metrics:
                result = optimizer.evaluate_prompt(
                    prompt=optimized_prompt,
                    dataset=dataset,
                    metric=metric_,
                    n_threads=4,
                )
                optimized_evaluation.append(
                    {
                        "metric_name": metric_.__name__,
                        "score": result,
                        "timestamp": time.time(),
                    }
                )
            optimized_evaluation_duration = time.time() - start_time_final_eval
            print(
                f"[{task_id}] Final evaluation complete in {optimized_evaluation_duration:.2f}s"
            )

            # Create result object
            result = TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status="Success",
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                initial_evaluation=TaskEvaluationResult(
                    metrics=initial_evaluation,  # type: ignore
                    duration_seconds=initial_evaluation_duration,
                ),
                optimized_prompt=optimized_prompt,
                optimized_evaluation=TaskEvaluationResult(
                    metrics=optimized_evaluation,  # type: ignore
                    duration_seconds=optimized_evaluation_duration,
                ),
                error_message=None,
                llm_calls_total_optimization=optimization_results.llm_calls,
                optimization_raw_result=optimization_results,
                timestamp_end=time.time(),
            )

            # Save result to Modal Volume
            result_dict = _save_result_to_volume(result, run_id)

            print(
                f"[{task_id}] Completed successfully in {time.time() - timestamp_start:.2f}s"
            )
            return result_dict

        except Exception as e:
            print(f"[{task_id}] Failed with error: {str(e)}")

            result = TaskResult(
                id=task_id,
                dataset_name=dataset_name,
                optimizer_name=optimizer_name,
                model_name=model_name,
                status="Failed",
                timestamp_start=timestamp_start,
                initial_prompt=initial_prompt,
                error_message=traceback.format_exc(),
                timestamp_end=time.time(),
            )

            # Save failed result to Volume
            result_dict = _save_result_to_volume(result, run_id)

            return result_dict


def _save_result_to_volume(result: "TaskResult", run_id: str) -> dict:
    """
    Save TaskResult to Modal Volume as JSON.

    Results are saved to: /results/{run_id}/tasks/{task_id}.json

    Args:
        result: TaskResult object to save
        run_id: Unique identifier for this benchmark run

    Returns:
        Dictionary representation of the result
    """

    # Create directory structure
    results_dir = Path("/results") / run_id / "tasks"
    results_dir.mkdir(parents=True, exist_ok=True)

    # Convert TaskResult to dict (handle Pydantic serialization)
    result_dict = (
        result.model_dump() if hasattr(result, "model_dump") else result.dict()
    )  # type: ignore

    # Convert non-serializable objects to strings
    result_dict = _make_serializable(result_dict)

    # Save to JSON file (sanitize filename to avoid issues with slashes in model names)
    safe_task_id = result.id.replace("/", "_")
    result_file = results_dir / f"{safe_task_id}.json"
    with open(result_file, "w") as f:
        json.dump(result_dict, f, indent=2)

    # Commit changes to Volume (makes them visible to other functions)
    results_volume.commit()

    return result_dict


def _make_serializable(obj: Any) -> Any:
    """Recursively convert objects to JSON-serializable types."""
    if isinstance(obj, dict):
        return {k: _make_serializable(v) for k, v in obj.items()}
    elif isinstance(obj, (list, tuple)):
        return [_make_serializable(item) for item in obj]
    elif hasattr(obj, "model_dump"):  # Pydantic v2
        return _make_serializable(obj.model_dump())
    elif hasattr(obj, "dict"):  # Pydantic v1
        return _make_serializable(obj.dict())
    elif hasattr(obj, "__dict__"):
        return _make_serializable(obj.__dict__)
    else:
        # Try to convert to string if not serializable
        try:
            json.dumps(obj)
            return obj
        except (TypeError, ValueError):
            return str(obj)
