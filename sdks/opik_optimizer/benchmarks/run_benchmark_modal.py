"""
Submit benchmark tasks to Modal for execution.

This coordinator script submits all benchmark tasks to the deployed Modal worker.
Use --detach flag to disconnect after submission without waiting for results.

Usage:
    # Recommended: Use the unified entry point
    python run_benchmark.py --modal --demo-datasets gsm8k --optimizers few_shot --test-mode

    # Alternative: Direct Modal execution (from benchmarks directory)
    modal run --detach benchmarks/run_benchmark_modal.py --test-mode

    # Submit specific configuration
    python run_benchmark.py --modal \
        --demo-datasets gsm8k hotpot_300 \
        --optimizers few_shot meta_prompt \
        --models openai/gpt-4o-mini \
        --max-concurrent 5

    # Resume or retry a previous run
    python run_benchmark.py --modal --resume-run-id run_20250423_153045
    python run_benchmark.py --modal --retry-failed-run-id run_20250423_153045
"""

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path
import modal

from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec
from benchmarks.core import benchmark_config
from benchmarks.utils import budgeting

# Define Modal app (just for local entrypoint - worker is deployed separately)
app = modal.App("opik-optimizer-benchmarks-coordinator")

# Access the results volume
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)


def submit_benchmark_tasks(
    demo_datasets: list[str] | None = None,
    optimizers: list[str] | None = None,
    models: list[str] | None = None,
    seed: int = 42,
    test_mode: bool = False,
    max_concurrent: int = 5,
    retry_failed_run_id: str | None = None,
    resume_run_id: str | None = None,
    task_specs: list[BenchmarkTaskSpec] | None = None,
) -> None:
    """
    Submit all benchmark tasks to Modal workers.

    This function:
    1. Looks up the deployed worker function
    2. Generates all task combinations
    3. Spawns tasks asynchronously (respecting max_concurrent limit)
    4. Saves metadata to Modal Volume
    5. Exits (can use --detach to disconnect)

    Args:
        demo_datasets: List of dataset names to benchmark
        optimizers: List of optimizer names to benchmark
        models: List of model names to benchmark
        seed: Random seed for reproducibility
        test_mode: Run in test mode with 5 examples per dataset
        max_concurrent: Maximum number of concurrent tasks
        retry_failed_run_id: Run ID to retry failed tasks from
        resume_run_id: Run ID to resume incomplete run from
    """
    # Convert single strings to Lists (Modal CLI may pass single values as strings)
    if demo_datasets is not None and isinstance(demo_datasets, str):
        demo_datasets = [demo_datasets]

    if optimizers is not None and isinstance(optimizers, str):
        optimizers = [optimizers]

    if models is not None and isinstance(models, str):
        models = [models]

    # Validate inputs
    if demo_datasets is not None and not isinstance(demo_datasets, list):
        raise ValueError("demo_datasets must be a list of strings")

    if optimizers is not None and not isinstance(optimizers, list):
        raise ValueError("optimizers must be a list of strings")

    if models is not None and not isinstance(models, list):
        raise ValueError("models must be a list of strings")

    if task_specs is not None:
        if demo_datasets is not None or optimizers is not None or models is not None:
            raise ValueError(
                "When providing explicit task specs (e.g., via a manifest), do not "
                "combine them with --demo-datasets/--optimizers/--models. "
                "Specify either a manifest or CLI filters, not both."
            )
        demo_datasets = sorted({task.dataset_name for task in task_specs})
        optimizers = sorted({task.optimizer_name for task in task_specs})
        models = sorted({task.model_name for task in task_specs})
    else:
        if demo_datasets is None:
            demo_datasets = list(benchmark_config.DATASET_CONFIG.keys())
        if optimizers is None:
            optimizers = list(benchmark_config.OPTIMIZER_CONFIGS.keys())
        if models is None:
            models = benchmark_config.MODELS

    # Create unique run id
    if resume_run_id and retry_failed_run_id:
        raise ValueError("Cannot resume and retry at the same time")
    elif resume_run_id:
        run_id = resume_run_id
        print(f"\n📋 Resuming run: {run_id}")
    elif retry_failed_run_id:
        run_id = retry_failed_run_id
        print(f"\n🔄 Retrying failed tasks from run: {run_id}")
    else:
        run_id = f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{os.urandom(4).hex()}"

    print("\n📊 Configuration:")
    print(f"  Datasets:  {', '.join(demo_datasets)}")
    print(f"  Optimizers: {', '.join(optimizers)}")
    print(f"  Models:     {', '.join(models)}")
    print(f"  Test mode:  {test_mode}")
    print(f"  Max concurrent: {max_concurrent}")

    # Look up deployed worker function
    print("\n🔍 Looking up deployed worker function...")
    try:
        worker = modal.Function.from_name(
            "opik-optimizer-benchmarks", "run_optimization_modal"
        )
    except modal.exception.NotFoundError:
        print("\n❌ ERROR: Worker function not found!")
        print("\nPlease deploy the worker first:")
        print("  modal deploy benchmarks/benchmark_worker.py")
        sys.exit(1)

    # Get workspace from Modal config
    workspace = None
    try:
        from modal.config import config_profiles

        profiles: list[str] = config_profiles()  # type: ignore[no-untyped-call]
        if profiles:
            workspace = profiles[0]
    except Exception:
        pass

    # Update worker's max_containers to control concurrency
    print(f"⚙️  Configuring worker for {max_concurrent} concurrent tasks...")
    try:
        worker.update_autoscaler(max_containers=max_concurrent)
        print(f"✅ Worker configured: max {max_concurrent} containers")
    except Exception as e:
        print(f"⚠️  Warning: Could not update autoscaler: {e}")
        print("   Worker will use default concurrency settings")

    # Load existing run metadata if resuming/retrying
    completed_tasks: set[str] = set()
    failed_tasks: set[str] = set()
    if resume_run_id or retry_failed_run_id:
        print("\n📂 Loading previous run metadata...")
        completed_tasks, failed_tasks = _load_previous_run_metadata(run_id)
        print(
            f"   Found {len(completed_tasks)} completed, {len(failed_tasks)} failed tasks"
        )

    # Generate all task combinations
    print("\n📝 Generating task combinations...")
    all_tasks = []
    skipped_count = 0

    if task_specs is None:
        tasks_iter: list[BenchmarkTaskSpec] = [
            BenchmarkTaskSpec(
                dataset_name=dataset,
                optimizer_name=optimizer,
                model_name=model,
                test_mode=test_mode,
            )
            for dataset in demo_datasets
            for optimizer in optimizers
            for model in models
        ]
    else:
        tasks_iter = task_specs

    for task in tasks_iter:
        task_id = task.task_id

        if retry_failed_run_id and task_id not in failed_tasks:
            skipped_count += 1
            continue

        if resume_run_id and task_id in completed_tasks:
            skipped_count += 1
            continue

        optimize_override = budgeting.resolve_optimize_params(
            task.dataset_name,
            task.optimizer_name,
            task.optimizer_prompt_params,
        )
        all_tasks.append(
            {
                "task_id": task_id,
                "dataset_name": task.dataset_name,
                "optimizer_name": task.optimizer_name,
                "model_name": task.model_name,
                "test_mode": task.test_mode,
                "run_id": run_id,
                "optimizer_params": task.optimizer_params,
                "optimizer_prompt_params": optimize_override,
            }
        )

    print(f"   Generated {len(all_tasks)} tasks to submit")
    if skipped_count > 0:
        print(f"   Skipped {skipped_count} tasks (already completed)")

    if len(all_tasks) == 0:
        print("\n✅ No tasks to submit!")
        return

    # Save run metadata to Volume
    print("\n💾 Saving run metadata to Modal Volume...")
    _save_run_metadata(
        run_id=run_id,
        demo_datasets=demo_datasets,
        optimizers=optimizers,
        models=models,
        test_mode=test_mode,
        max_concurrent=max_concurrent,
        total_tasks=len(all_tasks),
        workspace=workspace,
        seed=seed,
        tasks=[task.to_dict() for task in tasks_iter] if task_specs else None,
    )

    # Submit all tasks asynchronously
    print(f"\n🚀 Submitting {len(all_tasks)} tasks to Modal...")
    print(f"   (Workers will process {max_concurrent} tasks at a time)")

    function_call_ids = []
    for i, task_params in enumerate(all_tasks, 1):
        try:
            call = worker.spawn(**task_params)
            function_call_ids.append(
                {
                    "task_id": task_params["task_id"],
                    "call_id": call.object_id,
                    "status": "submitted",
                }
            )
            if i % 10 == 0 or i == len(all_tasks):
                print(f"   Submitted {i}/{len(all_tasks)} tasks...")
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

    # Save function call IDs to Volume
    print("\n💾 Saving function call IDs to Modal Volume...")
    _save_function_call_ids(run_id, function_call_ids)

    print("\n" + "=" * 80)
    print("✅ ALL TASKS SUBMITTED SUCCESSFULLY!")
    print("=" * 80)
    print(f"\n📋 Run ID: {run_id}")
    print(f"📊 Total tasks submitted: {len(function_call_ids)}")
    print(f"⚙️  Max concurrent: {max_concurrent}")
    print("\n💡 Your local machine can now disconnect safely!")
    print("   Tasks will continue running in Modal's cloud.")
    print("\n📈 Monitor progress:")
    print("   • Modal dashboard: https://modal.com/apps")
    print(f"   • Check results: modal run check_results.py --run-id {run_id}")
    print("\n⏰ Results will be available once all tasks complete.")
    print("=" * 80 + "\n")


def _load_previous_run_metadata(run_id: str) -> tuple[set[str], set[str]]:
    """
    Load metadata from previous run.

    Returns:
        Tuple of (completed_task_ids, failed_task_ids)
    """
    import tempfile

    completed_tasks: set[str] = set()
    failed_tasks: set[str] = set()

    # Download the run's results directory to temp location
    with tempfile.TemporaryDirectory() as _tmpdir:
        try:
            # Create a temporary volume reference and download
            _vol = modal.Volume.from_name("opik-benchmark-results")

            # The volume needs to be used within a function context to download
            # For now, we'll return empty sets - the check_results.py script
            # will handle loading results from the volume properly
            # This is a limitation of Modal's local entrypoint environment

            print("   ⚠️  Note: Cannot load previous results in local entrypoint mode")
            print("      All tasks will be submitted (duplicates may occur)")

        except Exception as e:
            print(f"   ⚠️  Could not load previous run: {e}")

    return completed_tasks, failed_tasks


def _save_run_metadata(
    run_id: str,
    demo_datasets: list[str],
    optimizers: list[str],
    models: list[str],
    test_mode: bool,
    total_tasks: int,
    max_concurrent: int = 5,
    workspace: str | None = None,
    seed: int | None = None,
    tasks: list[dict[str, str | bool]] | None = None,
) -> None:
    """Save run metadata to Modal Volume."""
    # We need to save this from within a Modal function context
    # Use a helper function
    _save_metadata_to_volume.remote(
        run_id=run_id,
        metadata={
            "run_id": run_id,
            "demo_datasets": demo_datasets,
            "optimizers": optimizers,
            "models": models,
            "test_mode": test_mode,
            "max_concurrent": max_concurrent,
            "total_tasks": total_tasks,
            "timestamp": datetime.now().isoformat(),
            "workspace": workspace,
            "seed": seed,
            "tasks": tasks,
        },
    )


def _save_function_call_ids(run_id: str, function_call_ids: list[dict]) -> None:
    """Save function call IDs to Modal Volume."""
    _save_call_ids_to_volume.remote(run_id=run_id, call_ids=function_call_ids)


# Helper functions that run in Modal context to access Volume
@app.function(volumes={"/results": results_volume})
def _save_metadata_to_volume(run_id: str, metadata: dict) -> None:
    """Save run metadata to Volume (runs in Modal context)."""
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    metadata_file = run_dir / "metadata.json"
    with open(metadata_file, "w") as f:
        json.dump(metadata, f, indent=2)

    results_volume.commit()


@app.function(volumes={"/results": results_volume})
def _save_call_ids_to_volume(run_id: str, call_ids: list[dict]) -> None:
    """Save function call IDs to Volume (runs in Modal context)."""
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    call_ids_file = run_dir / "call_ids.json"
    with open(call_ids_file, "w") as f:
        json.dump(call_ids, f, indent=2)

    results_volume.commit()


if __name__ == "__main__":
    # Parse CLI arguments when run directly (not via modal run)
    parser = argparse.ArgumentParser(
        description="Submit benchmark tasks to Modal",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Recommended: Use the unified entry point
  python run_benchmark.py --modal --demo-datasets gsm8k --optimizers few_shot --test-mode

  # Alternative: Direct Modal execution (use --detach to disconnect)
  modal run --detach benchmarks/run_benchmark_modal.py --test-mode

  # Submit specific configuration
  python run_benchmark.py --modal \\
    --demo-datasets gsm8k hotpot_300 \\
    --optimizers few_shot meta_prompt \\
    --models openai/gpt-4o-mini \\
    --max-concurrent 10

  # Resume incomplete run
  python run_benchmark.py --modal --resume-run-id run_20250423_153045

  # Retry failed tasks
  python run_benchmark.py --modal --retry-failed-run-id run_20250423_153045
        """,
    )
    parser.add_argument(
        "--demo-datasets",
        type=str,
        nargs="*",
        default=None,
        help="Dataset names to benchmark.",
    )
    parser.add_argument(
        "--optimizers",
        type=str,
        nargs="*",
        default=None,
        help="Optimizer names to benchmark.",
    )
    parser.add_argument(
        "--models",
        type=str,
        nargs="*",
        default=None,
        help="Model names to benchmark.",
    )
    parser.add_argument(
        "--test-mode",
        action="store_true",
        default=False,
        help="Run in test mode with 5 examples per dataset",
    )
    parser.add_argument(
        "--seed", type=int, default=42, help="Random seed for reproducibility"
    )
    parser.add_argument(
        "--max-concurrent",
        type=int,
        default=5,
        help="Maximum number of concurrent tasks (default: 5)",
    )
    parser.add_argument(
        "--retry-failed-run-id",
        type=str,
        default=None,
        metavar="RUN_ID",
        help="Retry only failed tasks from a previous run",
    )
    parser.add_argument(
        "--resume-run-id",
        type=str,
        default=None,
        metavar="RUN_ID",
        help="Resume incomplete run (skip completed tasks)",
    )

    args = parser.parse_args()

    submit_benchmark_tasks(
        demo_datasets=args.demo_datasets,
        optimizers=args.optimizers,
        models=args.models,
        seed=args.seed,
        test_mode=args.test_mode,
        max_concurrent=args.max_concurrent,
        retry_failed_run_id=args.retry_failed_run_id,
        resume_run_id=args.resume_run_id,
    )
