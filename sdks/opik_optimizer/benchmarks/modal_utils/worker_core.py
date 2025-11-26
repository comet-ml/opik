"""Core optimization logic for Modal workers (without Modal decorators)."""

import os
import time
import traceback
from typing import Any

import opik
from benchmarks.core.benchmark_task import TaskResult, TASK_STATUS_FAILED
from benchmarks.utils.task_runner import execute_task


def _dump_opik_configuration() -> None:
    """Dump Opik configuration for debugging."""
    print("=" * 80)
    print("OPIK CONFIGURATION (Worker)")
    print("=" * 80)

    # Environment variables
    print("\nEnvironment Variables:")
    env_vars = {
        "OPIK_API_KEY": os.getenv("OPIK_API_KEY"),
        "OPIK_URL_OVERRIDE": os.getenv("OPIK_URL_OVERRIDE"),
        "OPIK_WORKSPACE": os.getenv("OPIK_WORKSPACE"),
        "OPIK_PROJECT_NAME": os.getenv("OPIK_PROJECT_NAME"),
    }
    for key, value in env_vars.items():
        if value:
            if key == "OPIK_API_KEY":
                masked = value[:8] + "..." + value[-4:] if len(value) > 12 else "***"
                print(f"  {key}: {masked}")
            else:
                print(f"  {key}: {value}")
        else:
            print(f"  {key}: NOT SET")

    # Opik SDK config
    print("\nOpik SDK Configuration:")
    try:
        from opik.config import OpikConfig

        config = OpikConfig()
        print(f"  API Key present: {'YES' if config.api_key else 'NO'}")
        print(
            f"  Base URL: {config.url_override or 'DEFAULT (https://www.comet.com/opik/api)'}"
        )
        print(f"  Workspace: {config.workspace or 'NOT SET'}")
    except Exception as e:
        print(f"  Failed to load config: {e}")

    print("=" * 80)
    print()


def _ensure_opik_credentials() -> None:
    api_key = os.getenv("OPIK_API_KEY", "").strip()
    # Check URL override environment variable
    host = os.getenv("OPIK_URL_OVERRIDE")

    # Determine if this is a self-hosted instance
    is_self_hosted = bool(host and host != "https://www.comet.com/opik/api")

    print(f"Opik instance type: {'Self-hosted' if is_self_hosted else 'Comet Cloud'}")
    print(f"API key required: {'NO' if is_self_hosted else 'YES'}")
    print(f"API key present: {'YES' if api_key else 'NO'}")

    # API key is only required for Comet Cloud
    if not is_self_hosted and not api_key:
        raise RuntimeError(
            "OPIK_API_KEY is missing or empty for Comet Cloud. "
            "Ensure the `opik-benchmarks` secret includes OPIK_API_KEY. "
            "For self-hosted instances, set OPIK_BASE_URL or OPIK_HOST and omit OPIK_API_KEY."
        )

    # Optional lightweight ping to fail fast on bad keys
    print("Testing Opik client connection...")
    try:
        client = opik.Opik()
        # `get_current_workspace` is a cheap way to validate the key (if method exists)
        if hasattr(client, "get_current_workspace"):
            client.get_current_workspace()  # type: ignore[attr-defined]
        print("✓ Opik client connection successful")
    except Exception as exc:
        # Only raise for Comet Cloud or if we have an API key
        if not is_self_hosted or api_key:
            raise RuntimeError(
                f"Opik credential check failed (host={host or 'default'}): {exc}"
            ) from exc
        # For self-hosted without API key, just warn but continue
        print(f"⚠ Opik connection check failed (self-hosted, no API key): {exc}")


def run_optimization_task(
    task_id: str,
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    model_parameters: dict[str, Any] | None,
    test_mode: bool,
    optimizer_params_override: dict[str, Any] | None = None,
    optimizer_prompt_params_override: dict[str, Any] | None = None,
    datasets: dict[str, Any] | None = None,
    metrics: list[str | dict[str, Any]] | None = None,
    prompt_messages: list[dict[str, Any]] | None = None,
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
    # Disable tracing for benchmark jobs to avoid Opik span volume by default
    os.environ.setdefault("OPIK_TRACK_DISABLE", "true")
    os.environ.setdefault("OPIK_DATASET_SKIP_EXISTING", "true")
    try:
        opik.set_tracing_active(False)
    except Exception:
        pass

    # Dump configuration for debugging
    _dump_opik_configuration()

    # Ensure credentials are valid
    _ensure_opik_credentials()

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
            datasets=datasets,
            metrics=metrics,
            prompt_messages=prompt_messages,
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
