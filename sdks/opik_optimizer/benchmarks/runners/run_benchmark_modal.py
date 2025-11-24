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
from typing import Any

try:
    import modal
except ModuleNotFoundError:
    print(
        "❌ Modal is not installed. Install it with `pip install modal` "
        "or rerun without --modal."
    )
    sys.exit(1)

from benchmarks.benchmark_constants import (
    DEFAULT_MAX_CONCURRENT,
    MODAL_SECRET_NAME,
)
from benchmarks.core import benchmark_config
from benchmarks.core.benchmark_taskspec import BenchmarkTaskSpec
from benchmarks.utils.budgeting import resolve_optimize_params
from benchmarks.utils.task_runner import preflight_tasks
from benchmarks.utils import modal_helper

from rich import box
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

console = Console()

# Define Modal app (just for local entrypoint - worker is deployed separately)
app = modal.App("opik-optimizer-benchmarks-coordinator")

# Access the results volume
results_volume = modal.Volume.from_name(
    "opik-benchmark-results", create_if_missing=True
)
# Coordinator image (needs opik_optimizer deps for imports)
coordinator_image = (
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
)


def submit_benchmark_tasks(
    demo_datasets: list[str] | None = None,
    optimizers: list[str] | None = None,
    models: list[str] | None = None,
    seed: int = 42,
    test_mode: bool = False,
    max_concurrent: int | None = DEFAULT_MAX_CONCURRENT,
    retry_failed_run_id: str | None = None,
    resume_run_id: str | None = None,
    task_specs: list[BenchmarkTaskSpec] | None = None,
    manifest_path: str | None = None,
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
        max_concurrent: Maximum number of concurrent tasks (also used for Modal max_containers)
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
        console.print(f"[bold]🆕 Run ID:[/bold] [cyan]{run_id}[/cyan]")

    # Confirm Modal workspace/profile is available
    workspace = None
    try:
        from modal.config import config_profiles

        profiles = list(config_profiles())  # type: ignore[no-untyped-call]
        if profiles:
            workspace = profiles[0]
    except Exception:
        workspace = None

    if workspace is None:
        print(
            "\n❌ ERROR: No active Modal workspace/profile detected."
            "\nPlease log in or set a profile first:"
            "\n  modal token new"
        )
        sys.exit(1)

    # Env key summary and suggested secret command
    env_summary = modal_helper.summarize_env()
    opik_cfg = modal_helper.read_opik_config()
    missing_req = env_summary["missing_required"]
    summary_table = Table(show_header=False, box=box.SIMPLE, padding=(0, 1))
    summary_table.add_row("Run ID", run_id)
    summary_table.add_row("Datasets", ", ".join(demo_datasets))
    summary_table.add_row("Optimizers", ", ".join(optimizers))
    summary_table.add_row("Models", ", ".join(models))
    summary_table.add_row("Test mode", str(test_mode))
    summary_table.add_row(
        "Max concurrent",
        str(max_concurrent if max_concurrent is not None else DEFAULT_MAX_CONCURRENT),
    )
    summary_table.add_row("Manifest", manifest_path or "N/A")
    summary_table.add_row(
        "Present required", ", ".join(env_summary["present_required"]) or "-"
    )
    if missing_req:
        summary_table.add_row("Missing required", ", ".join(missing_req))
        if opik_cfg.get("api_key"):
            summary_table.add_row(
                "opik config", "api_key found in ~/.opik.config (export it)"
            )
    summary_table.add_row(
        "Present optional", ", ".join(env_summary["present_optional"]) or "-"
    )
    host_display = (
        ", ".join(env_summary["present_host"])
        if env_summary["present_host"]
        else opik_cfg.get("url_override", "-")
    )
    summary_table.add_row("OPIK host override", host_display or "-")
    # Volume check
    volume_status = "[green]ok[/green]"
    try:
        modal.Volume.from_name("opik-benchmark-results")
    except Exception as e:
        volume_status = f"[red]{e}[/red]"
    summary_table.add_row("Volume", f"opik-benchmark-results ({volume_status})")
    secret_cmd = modal_helper.build_secret_command(MODAL_SECRET_NAME, env_summary)
    summary_table.add_row("Secret from env", f"[dim]{secret_cmd}[/dim]")
    if env_summary["missing_optional"]:
        template_cmd = modal_helper.build_placeholder_secret_command(MODAL_SECRET_NAME)
        summary_table.add_row(
            "Template",
            f"[dim]{template_cmd}[/dim]\nInclude optional keys by exporting them, then rerun secret.",
        )
    console.print(
        Panel(
            summary_table,
            title="Modal Setup",
            border_style="cyan",
            padding=(0, 1),
            width=100,
        )
    )
    # Decide if we need OPIK_API_KEY: required for default Comet host, optional otherwise
    needs_key = False
    host_override = (
        env_summary["present_host"][0]
        if env_summary["present_host"]
        else opik_cfg.get("url_override")
    )
    if host_override:
        if "comet.com/opik" in host_override:
            needs_key = True
    else:
        # No override; assume default Comet host
        needs_key = True

    if needs_key and ("OPIK_API_KEY" in missing_req):
        print(
            "\n❌ OPIK_API_KEY is required for the default Comet host. "
            "Please export it and recreate the secret."
        )
        sys.exit(1)

    # Look up deployed worker function
    console.print("🔧 Looking up deployed worker function...")
    try:
        worker = modal.Function.from_name(
            "opik-optimizer-benchmarks", "run_optimization_modal"
        )
    except modal.exception.NotFoundError:
        print("\n❌ ERROR: Modal worker function not found!")
        print(
            "\nPlease deploy the worker and ensure it exists in your active workspace:"
        )
        print("  pip install modal")
        print("  modal deploy benchmarks/benchmark_worker.py")
        sys.exit(1)

    # Update worker's max_containers to control concurrency
    max_containers = max_concurrent or DEFAULT_MAX_CONCURRENT
    console.print(f"🔧 Configuring worker for {max_containers} concurrent tasks...")
    try:
        worker.update_autoscaler(max_containers=max_containers)
        console.print(f"🔧 Worker configured: max {max_containers} containers")
    except modal.exception.NotFoundError:
        print(
            "\n❌ ERROR: Worker function not found in your current Modal environment."
            "\nDeploy (or redeploy) the worker then retry:"
            "\n  modal deploy benchmarks/benchmark_worker.py\n"
        )
        sys.exit(1)
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
    console.print("🔧 Generating task combinations...")
    all_tasks = []
    skipped_count = 0

    if task_specs is None:
        tasks_iter: list[BenchmarkTaskSpec] = [
            BenchmarkTaskSpec(
                dataset_name=dataset,
                optimizer_name=optimizer,
                model_name=model,
                test_mode=test_mode,
                model_parameters=None,
            )
            for dataset in demo_datasets
            for optimizer in optimizers
            for model in models
        ]
    else:
        tasks_iter = task_specs

    # Preflight before submitting remotely to avoid mid-run failures.
    preflight_report = preflight_tasks(
        tasks_iter,
        info={
            "manifest_path": None,
            "checkpoint_dir": "/results",
            "run_id": run_id,
        },
    )

    for task in tasks_iter:
        task_id = task.task_id

        if retry_failed_run_id and task_id not in failed_tasks:
            skipped_count += 1
            continue

        if resume_run_id and task_id in completed_tasks:
            skipped_count += 1
            continue

        optimize_override = resolve_optimize_params(
            task.dataset_name, task.optimizer_name, task.optimizer_prompt_params
        )
        all_tasks.append(
            {
                "task_id": task_id,
                "dataset_name": task.dataset_name,
                "optimizer_name": task.optimizer_name,
                "model_name": task.model_name,
                "model_parameters": task.model_parameters,
                "test_mode": task.test_mode,
                "run_id": run_id,
                "optimizer_params": task.optimizer_params,
                "optimizer_prompt_params": optimize_override,
                "datasets": task.datasets,
                "metrics": task.metrics,
                "prompt_messages": task.prompt_messages,
            }
        )

    print(f"   Generated {len(all_tasks)} tasks to submit")
    if skipped_count > 0:
        print(f"   Skipped {skipped_count} tasks (already completed)")

    if len(all_tasks) == 0:
        print("\n✅ No tasks to submit!")
        return

    # Save run metadata to Volume
    console.print(
        Panel(
            "Saving run metadata to Modal Volume...\n"
            "[dim]If this is the first run, Modal may build the coordinator image (can take ~1-2 minutes).[/dim]",
            border_style="magenta",
            width=100,
        )
    )
    try:
        _save_run_metadata(
            run_id=run_id,
            demo_datasets=demo_datasets,
            optimizers=optimizers,
            models=models,
            test_mode=test_mode,
            max_concurrent=max_concurrent or DEFAULT_MAX_CONCURRENT,
            total_tasks=len(all_tasks),
            workspace=workspace,
            seed=seed,
            tasks=[task.to_dict() for task in tasks_iter] if task_specs else None,
            preflight=preflight_report.model_dump(),
            manifest_path=manifest_path,
        )
    except Exception as e:
        print(
            f"❌ ERROR: Unable to save run metadata to Modal Volume: {e}\n"
            "Confirm you are logged in to Modal (`modal token new`), the volume exists,\n"
            "and the coordinator functions are deployed. Aborting submission."
        )
        sys.exit(1)

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
    try:
        _save_function_call_ids(run_id, function_call_ids)
    except Exception as e:
        print(
            f"❌ ERROR: Unable to save function call IDs to Modal Volume: {e}\n"
            "Aborting to avoid orphaned submissions. Please check your Modal setup."
        )
        sys.exit(1)

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
    print(
        f"   • Check results: modal run benchmarks/check_results.py --run-id {run_id} --show-errors"
    )
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
    preflight: dict | None = None,
    manifest_path: str | None = None,
) -> None:
    """Save run metadata to Modal Volume."""
    # We need to save this from within a Modal function context
    # Use a helper function
    payload = {
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
        "preflight": preflight,
        "manifest_path": manifest_path,
    }
    print("   -> Sending metadata to volume...")
    try:
        try:
            fn = modal.Function.lookup(  # type: ignore[attr-defined]
                "opik-optimizer-benchmarks-coordinator",
                "_save_metadata_to_volume",
            )
            fn.call(run_id=run_id, metadata=payload)
        except Exception:
            f = _save_metadata_to_volume.remote(run_id=run_id, metadata=payload)
            if f is None:
                print(
                    "   -> metadata helper returned None; skipping metadata write (deploy coordinator to enable)."
                )
                return
            f.get(timeout=300)
        print("   -> Metadata saved.")
    except Exception as e:
        print(
            f"⚠️  Warning: Unable to save run metadata to Modal Volume: {e}\n"
            "    Continuing without writing metadata (tasks will still submit if worker is reachable).\n"
            "    If this persists, deploy the coordinator:\n"
            "      modal deploy benchmarks/runners/run_benchmark_modal.py"
        )


def _save_function_call_ids(run_id: str, function_call_ids: list[dict]) -> None:
    """Save function call IDs to Modal Volume."""
    print("   -> Sending call IDs to volume...")
    try:
        try:
            fn = modal.Function.lookup(  # type: ignore[attr-defined]
                "opik-optimizer-benchmarks-coordinator",
                "_save_call_ids_to_volume",
            )
            fn.call(run_id=run_id, call_ids=function_call_ids)
        except Exception:
            f = _save_call_ids_to_volume.remote(
                run_id=run_id, call_ids=function_call_ids
            )
            if f is None:
                print(
                    "   -> call_ids helper returned None; skipping call id write (deploy coordinator to enable)."
                )
                return
            f.get(timeout=300)
        print("   -> Call IDs saved.")
    except Exception as e:
        print(
            f"⚠️  Warning: Unable to save call IDs to Modal Volume: {e}\n"
            "    Continuing without writing call IDs. To fix, deploy coordinator:\n"
            "      modal deploy benchmarks/runners/run_benchmark_modal.py"
        )


# Helper functions that run in Modal context to access Volume
@app.function(image=coordinator_image, volumes={"/results": results_volume})
def _save_metadata_to_volume(run_id: str, metadata: dict[str, Any]) -> None:
    """Save run metadata to Volume (runs in Modal context)."""
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    metadata_file = run_dir / "metadata.json"
    with open(metadata_file, "w") as f:
        json.dump(metadata, f, indent=2)

    results_volume.commit()


@app.function(image=coordinator_image, volumes={"/results": results_volume})
def _save_call_ids_to_volume(run_id: str, call_ids: list[dict[str, Any]]) -> None:
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
        default=DEFAULT_MAX_CONCURRENT,
        help=f"Maximum number of concurrent tasks (default: {DEFAULT_MAX_CONCURRENT})",
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
