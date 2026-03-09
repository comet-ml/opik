from __future__ import annotations

import os
import subprocess
import time
import traceback
import configparser
import logging
import sys
from pathlib import Path
from collections.abc import Iterable
from typing import Any

import opik
from benchmarks.core.types import TASK_STATUS_FAILED, TaskResult
from benchmarks.core.types import TASK_STATUS_RUNNING
from benchmarks.core.evaluation import run_task_evaluation
from benchmarks.core.planning import TaskPlan
from benchmarks.engines.base import BenchmarkEngine, EngineCapabilities, EngineRunResult
from benchmarks.engines.modal.volume import save_result_to_volume
from opik_optimizer.constants import (
    DEFAULT_BENCHMARK_MODAL_SECRET_NAME,
    DEFAULT_BENCHMARK_WORKER_TIMEOUT_SECONDS,
)

try:
    import modal
except ModuleNotFoundError:
    modal = None  # type: ignore[assignment]

REQUIRED_KEYS = ["OPIK_API_KEY"]
OPTIONAL_KEYS = [
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "GOOGLE_API_KEY",
    "GEMINI_API_KEY",
    "OPENROUTER_API_KEY",
]
OPIK_HOST_KEYS = ["OPIK_URL_OVERRIDE", "OPIK_HOST"]
logger = logging.getLogger(__name__)

# Modal worker runtime knobs (env-overridable):
# - MODAL_MAX_RETRIES: int >= 0
# - MODAL_INITIAL_DELAY: float > 0
# - MODAL_BACKOFF_COEFFICIENT: float >= 1
# - MODAL_CPU: float > 0
# - MODAL_MEMORY: int > 0 (MiB)


def _env_int(name: str, default: int, *, minimum: int | None = None) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw)
    except ValueError:
        logger.warning("Invalid %s=%r; using default %s", name, raw, default)
        return default
    if minimum is not None and value < minimum:
        logger.warning(
            "Out-of-range %s=%s (min=%s); using default %s",
            name,
            value,
            minimum,
            default,
        )
        return default
    return value


def _env_float(name: str, default: float, *, minimum: float | None = None) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        logger.warning("Invalid %s=%r; using default %s", name, raw, default)
        return default
    if minimum is not None and value < minimum:
        logger.warning(
            "Out-of-range %s=%s (min=%s); using default %s",
            name,
            value,
            minimum,
            default,
        )
        return default
    return value


MODAL_MAX_RETRIES = _env_int("MODAL_MAX_RETRIES", 2, minimum=0)
MODAL_INITIAL_DELAY = _env_float("MODAL_INITIAL_DELAY", 10.0, minimum=0.0001)
MODAL_BACKOFF_COEFFICIENT = _env_float("MODAL_BACKOFF_COEFFICIENT", 2.0, minimum=1.0)
MODAL_CPU = _env_float("MODAL_CPU", 2.0, minimum=0.0001)
MODAL_MEMORY = _env_int("MODAL_MEMORY", 4096, minimum=1)


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
    except (configparser.Error, OSError) as exc:
        logger.error("Failed to read Opik config at %s: %s", path, exc)
        raise


def _dump_opik_configuration() -> None:
    logger.info("=" * 80)
    logger.info("OPIK CONFIGURATION (Worker)")
    logger.info("=" * 80)
    logger.info("Environment Variables:")
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
                logger.info("  %s: %s", key, masked)
            else:
                logger.info("  %s: %s", key, value)
        else:
            logger.info("  %s: NOT SET", key)

    logger.info("Opik SDK Configuration:")
    try:
        from opik.config import OpikConfig

        config = OpikConfig()
        logger.info("  API Key present: %s", "YES" if config.api_key else "NO")
        logger.info(
            "  Base URL: %s",
            config.url_override or "DEFAULT (https://www.comet.com/opik/api)",
        )
        logger.info("  Workspace: %s", config.workspace or "NOT SET")
    except Exception as e:
        logger.warning("  Failed to load config: %s", e)

    logger.info("=" * 80)


def _ensure_opik_credentials() -> None:
    api_key = os.getenv("OPIK_API_KEY", "").strip()
    host = os.getenv("OPIK_URL_OVERRIDE") or os.getenv("OPIK_HOST")
    is_self_hosted = bool(host and host != "https://www.comet.com/opik/api")

    logger.info(
        "Opik instance type: %s", "Self-hosted" if is_self_hosted else "Comet Cloud"
    )
    logger.info("API key required: %s", "NO" if is_self_hosted else "YES")
    logger.info("API key present: %s", "YES" if api_key else "NO")

    if not is_self_hosted and not api_key:
        raise RuntimeError(
            "OPIK_API_KEY is missing or empty for Comet Cloud. "
            "Ensure the `opik-benchmarks` secret includes OPIK_API_KEY. "
            "For self-hosted instances, set OPIK_URL_OVERRIDE or OPIK_HOST and omit OPIK_API_KEY."
        )

    logger.info("Testing Opik client connection...")
    try:
        client = opik.Opik()
        if hasattr(client, "get_current_workspace"):
            client.get_current_workspace()  # type: ignore[attr-defined]
        logger.info("Opik client connection successful")
    except Exception as exc:
        if not is_self_hosted or api_key:
            raise RuntimeError(
                f"Opik credential check failed (host={host or 'default'}): {exc}"
            ) from exc
        logger.warning(
            "Opik connection check failed (self-hosted, no API key): %s", exc
        )


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
    logger.info("[%s] Starting optimization...", task_id)
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
        logger.info(
            "[%s] Completed successfully in %.2fs",
            task_id,
            time.time() - timestamp_start,
        )
        return result
    except Exception as e:
        logger.error("[%s] Failed with error: %s", task_id, str(e))
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


if modal is not None:
    logger.info(
        "Modal worker config: retries=%s initial_delay=%s backoff=%s cpu=%s memory=%sMiB",
        MODAL_MAX_RETRIES,
        MODAL_INITIAL_DELAY,
        MODAL_BACKOFF_COEFFICIENT,
        MODAL_CPU,
        MODAL_MEMORY,
    )
    app = modal.App("opik-optimizer-benchmarks")
    image = (
        modal.Image.debian_slim(python_version="3.12")
        .add_local_dir(
            local_path=os.path.abspath(
                os.path.join(os.path.dirname(__file__), os.pardir, os.pardir, os.pardir)
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
        .add_local_dir(
            local_path=os.path.abspath(
                os.path.join(os.path.dirname(__file__), os.pardir, os.pardir)
            ),
            remote_path="/root/benchmarks",
            ignore=["__pycache__", ".venv", "benchmark_results"],
            copy=True,
        )
    )
    results_volume = modal.Volume.from_name(
        "opik-benchmark-results", create_if_missing=True
    )
    modal_secrets = [modal.Secret.from_name(DEFAULT_BENCHMARK_MODAL_SECRET_NAME)]

    @app.function(
        image=image,
        volumes={"/results": results_volume},
        secrets=modal_secrets,
        timeout=DEFAULT_BENCHMARK_WORKER_TIMEOUT_SECONDS,
        retries=modal.Retries(
            max_retries=MODAL_MAX_RETRIES,
            initial_delay=MODAL_INITIAL_DELAY,
            backoff_coefficient=MODAL_BACKOFF_COEFFICIENT,
        ),
        cpu=MODAL_CPU,
        memory=MODAL_MEMORY,
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
        sys.path.insert(0, "/root/benchmarks")
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
        save_result_to_volume(running_result, run_id, results_volume)

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
        result.timestamp_start = timestamp_start
        return save_result_to_volume(result, run_id, results_volume)


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
        from benchmarks.run_benchmark_modal import app, submit_benchmark_tasks

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

        return EngineRunResult(engine=self.name, status="succeeded")

    def deploy(self) -> EngineRunResult:
        subprocess.run(
            ["modal", "deploy", "benchmarks/engines/modal/engine.py"],
            check=True,
        )
        return EngineRunResult(
            engine=self.name,
            status="succeeded",
            metadata={"deployed": ["modal_engine"]},
        )
