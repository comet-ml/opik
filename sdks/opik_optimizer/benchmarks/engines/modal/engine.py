from __future__ import annotations

import os
import subprocess
import time
import traceback
import configparser
from pathlib import Path
from collections.abc import Iterable
from typing import Any

import opik
from benchmarks.core.benchmark_task import TASK_STATUS_FAILED, TaskResult
from benchmarks.core.evaluation import run_task_evaluation
from benchmarks.core.planning import TaskPlan
from benchmarks.engines.base import BenchmarkEngine, EngineCapabilities, EngineRunResult

REQUIRED_KEYS = ["OPIK_API_KEY"]
OPTIONAL_KEYS = [
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "GOOGLE_API_KEY",
    "GEMINI_API_KEY",
    "OPENROUTER_API_KEY",
]
OPIK_HOST_KEYS = ["OPIK_URL_OVERRIDE", "OPIK_HOST"]


def _collect(keys: Iterable[str]) -> tuple[list[str], list[str]]:
    present: list[str] = []
    missing: list[str] = []
    for k in keys:
        (present if os.getenv(k) else missing).append(k)
    return present, missing


def summarize_env() -> dict[str, list[str]]:
    present_req, missing_req = _collect(REQUIRED_KEYS)
    present_opt, missing_opt = _collect(OPTIONAL_KEYS)
    present_host, missing_host = _collect(OPIK_HOST_KEYS)
    return {
        "present_required": present_req,
        "missing_required": missing_req,
        "present_optional": present_opt,
        "missing_optional": missing_opt,
        "present_host": present_host,
        "missing_host": missing_host,
    }


def build_secret_command(secret_name: str, env_summary: dict[str, list[str]]) -> str:
    keys = env_summary["present_required"] + env_summary["present_optional"]
    if not keys:
        return (
            f"# Export your keys, then run: modal secret create {secret_name} "
            'OPIK_API_KEY="$OPIK_API_KEY" OPENAI_API_KEY="$OPENAI_API_KEY" ...'
        )
    parts = [f'{k}="${{{k}}}"' for k in keys]
    return f"modal secret create {secret_name} " + " ".join(parts)


def build_placeholder_secret_command(secret_name: str) -> str:
    keys = REQUIRED_KEYS + OPTIONAL_KEYS
    parts = [f'{k}="YOUR_{k}_HERE"' for k in keys]
    return f"modal secret create {secret_name} " + " ".join(parts)


def read_opik_config(config_path: str | None = None) -> dict[str, str]:
    path = Path(config_path or Path.home() / ".opik.config")
    if not path.exists():
        return {}
    parser = configparser.ConfigParser()
    try:
        parser.read(path)
        api_key = parser.get("opik", "api_key", fallback=None)
        url_override = parser.get("opik", "url_override", fallback=None)
        workspace = parser.get("opik", "workspace", fallback=None)
        data: dict[str, str] = {}
        if api_key:
            data["api_key"] = api_key
        if url_override:
            data["url_override"] = url_override
        if workspace:
            data["workspace"] = workspace
        return data
    except Exception:
        return {}


def _dump_opik_configuration() -> None:
    print("=" * 80)
    print("OPIK CONFIGURATION (Worker)")
    print("=" * 80)

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
    host = os.getenv("OPIK_URL_OVERRIDE")
    is_self_hosted = bool(host and host != "https://www.comet.com/opik/api")

    print(f"Opik instance type: {'Self-hosted' if is_self_hosted else 'Comet Cloud'}")
    print(f"API key required: {'NO' if is_self_hosted else 'YES'}")
    print(f"API key present: {'YES' if api_key else 'NO'}")

    if not is_self_hosted and not api_key:
        raise RuntimeError(
            "OPIK_API_KEY is missing or empty for Comet Cloud. "
            "Ensure the `opik-benchmarks` secret includes OPIK_API_KEY. "
            "For self-hosted instances, set OPIK_BASE_URL or OPIK_HOST and omit OPIK_API_KEY."
        )

    print("Testing Opik client connection...")
    try:
        client = opik.Opik()
        if hasattr(client, "get_current_workspace"):
            client.get_current_workspace()  # type: ignore[attr-defined]
        print("✓ Opik client connection successful")
    except Exception as exc:
        if not is_self_hosted or api_key:
            raise RuntimeError(
                f"Opik credential check failed (host={host or 'default'}): {exc}"
            ) from exc
        print(f"Warning: Opik connection check failed (self-hosted, no API key): {exc}")


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
    os.environ.setdefault("OPIK_TRACK_DISABLE", "true")
    os.environ.setdefault("OPIK_DATASET_SKIP_EXISTING", "true")
    try:
        opik.set_tracing_active(False)
    except Exception:
        pass

    _dump_opik_configuration()
    _ensure_opik_credentials()

    timestamp_start = time.time()
    print(f"[{task_id}] Starting optimization...")
    try:
        result = run_task_evaluation(
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


class ModalEngine(BenchmarkEngine):
    name = "modal"
    capabilities = EngineCapabilities(
        supports_deploy=True,
        supports_resume=True,
        supports_retry_failed=True,
        supports_live_logs=True,
        supports_remote_storage=True,
    )

    def run(self, plan: TaskPlan) -> EngineRunResult:
        from benchmarks.runners.run_benchmark_modal import app, submit_benchmark_tasks

        with app.run():
            submit_benchmark_tasks(
                demo_datasets=plan.demo_datasets,
                optimizers=plan.optimizers,
                models=plan.models,
                seed=plan.seed,
                test_mode=plan.test_mode,
                max_concurrent=plan.max_concurrent,
                retry_failed_run_id=plan.retry_failed_run_id,
                resume_run_id=plan.resume_run_id,
                task_specs=plan.tasks,
                manifest_path=plan.manifest_path,
            )

        return EngineRunResult(engine=self.name)

    def deploy(self) -> EngineRunResult:
        subprocess.run(
            ["modal", "deploy", "benchmarks/runners/benchmark_worker.py"],
            check=True,
        )
        subprocess.run(
            ["modal", "deploy", "benchmarks/runners/run_benchmark_modal.py"],
            check=True,
        )
        return EngineRunResult(
            engine=self.name,
            metadata={"deployed": ["benchmark_worker", "run_benchmark_modal"]},
        )
