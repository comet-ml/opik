"""
Optimizer job processor for Optimization Studio.

This module handles optimization jobs from the Java backend via Redis Queue (RQ).
Each optimization runs in an isolated subprocess for:
- Customer isolation (separate SDK clients, API keys)
- Memory isolation
- Crash isolation (one optimization failing doesn't affect others)

Logs from the subprocess are captured and streamed to Redis for S3 sync.
"""

import logging
import os
from typing import Any, Dict, Tuple, cast

from opentelemetry import trace

from opik_backend.executor_isolated import IsolatedSubprocessExecutor
from opik_backend.subprocess_logger import create_optimization_log_collector
from opik_backend.studio import (
    OPIK_GATEWAY_BASE_URL,
    OptimizationJobContext,
    OPTIMIZATION_TIMEOUT_SECS,
    CancellationHandle,
    JobMessageParseError,
)
from opik_backend.studio.config import OPIK_URL
from opik_backend.studio.errors import to_user_facing_message
from opik_backend.studio.status_manager import STATUS_UPDATE_MAX_RETRIES
from opik_backend.studio.types import (
    OptimizationCancelledResult,
    OptimizationJobResult,
    OptimizationRunResult,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# Path to the optimizer runner script
OPTIMIZER_RUNNER_PATH = os.path.join(
    os.path.dirname(__file__),
    "optimizer_runner.py"
)

# Payload type constant for optimization jobs
PAYLOAD_TYPE_OPTIMIZATION = "optimization"


def _mark_optimization_error(context: OptimizationJobContext) -> None:
    """Best-effort transition of the optimization to ERROR from the parent worker.

    The subprocess only advances status once it reaches ``optimization_lifecycle`` (mark_running).
    Any failure before that — bad stdin, import error, config parse error, Opik client init — leaves
    the run stuck on INITIALIZED because the subprocess had no status manager to call. This runs in the
    parent, which still has the job context, so it can flip the status and surface the failure fast
    instead of waiting for the backend reaper's timeout.

    Builds its own client with explicit args (no os.environ mutation) so it is safe to call from the
    concurrent worker threads. Never raises: the backend stalled-run reaper is the ultimate backstop if
    this cannot reach the API (which is also the case that made the run stuck in the first place).
    """
    try:
        import opik

        client = opik.Opik(
            workspace=context.workspace_name,
            api_key=context.opik_api_key or None,
            host=OPIK_URL or None,
            _show_misconfiguration_message=False,
        )
        client.rest_client.optimizations.update_optimizations_by_id(
            str(context.optimization_id),
            status="error",
            request_options={"max_retries": STATUS_UPDATE_MAX_RETRIES},
        )
        logger.info(
            "Marked optimization %s as ERROR from parent worker", context.optimization_id
        )
    except Exception as mark_error:
        logger.error(
            "Failed to mark optimization %s as ERROR from parent worker: %s",
            context.optimization_id,
            mark_error,
            exc_info=True,
        )


def _parse_job_message(args: Tuple[Any, ...], kwargs: Dict[str, Any]) -> Dict[str, Any]:
    """Parse job message from args or kwargs.
    
    Args:
        args: Job arguments tuple
        kwargs: Job keyword arguments dict
        
    Returns:
        Job message dictionary
        
    Raises:
        JobMessageParseError: If job message cannot be parsed
    """
    if args and isinstance(args[0], dict):
        return args[0]
    if kwargs:
        return kwargs
    raise JobMessageParseError("No job message found in args or kwargs")


def process_optimizer_job(*args: Any, **kwargs: Any) -> OptimizationJobResult:
    """Process an optimizer job from the Java backend.
    
    This is the main entry point for Optimization Studio jobs. It:
    1. Parses the job message
    2. Creates an isolated subprocess executor
    3. Sets up log collection (Redis-backed)
    4. Runs the optimization in the subprocess
    5. Returns the result
    
    The actual optimization logic runs in optimizer_runner.py in a subprocess.
    Status updates happen via the Opik SDK inside the subprocess.
    Logs are captured and streamed to Redis for S3 sync.
    
    Expected job message structure:
    {
        "optimization_id": "uuid",
        "workspace_id": "workspace-id",
        "workspace_name": "workspace-name", 
        "config": {
            "dataset_name": "dataset-name",
            "prompt": {"messages": [{"role": "...", "content": "..."}]},
            "llm_model": {"model": "openai/gpt-4o", "parameters": {...}},
            "evaluation": {"metrics": [{"type": "...", "parameters": {...}}]},
            "optimizer": {"type": "gepa", "parameters": {...}}
        },
        "opik_api_key": "optional-api-key-for-cloud"
    }
    
    Args:
        *args: Job arguments (first arg should be job message dict)
        **kwargs: Job keyword arguments (or job message as kwargs)
        
    Returns:
        Dictionary with optimization results
        
    Raises:
        ValueError: If job message is invalid
        Exception: Any error during optimization
    """
    with tracer.start_as_current_span("process_optimizer_job") as span:
        # Parse job message first (don't log raw args/kwargs - they contain API keys)
        job_message = _parse_job_message(args, kwargs)
        context = OptimizationJobContext.from_job_message(job_message)
        
        # Set span attributes for tracing
        span.set_attribute("optimization_id", str(context.optimization_id))
        span.set_attribute("workspace_id", context.workspace_id)
        span.set_attribute("workspace_name", context.workspace_name)
        
        logger.debug(
            f"Processing Optimization Studio job: {context.optimization_id} "
            f"for workspace: {context.workspace_name}"
        )
        
        # Route all LLM calls through the Opik backend gateway.
        # LiteLLM reads OPENAI_API_BASE and uses it as an OpenAI-compatible endpoint.
        env_vars = {}
        if OPIK_GATEWAY_BASE_URL:
            env_vars["OPENAI_API_BASE"] = OPIK_GATEWAY_BASE_URL
        env_vars["OPENAI_API_KEY"] = context.opik_api_key or "opik-local"
        
        # Mark subprocess as Optimization Studio execution (SDK display behavior).
        env_vars["OPIK_OPTIMIZATION_STUDIO"] = "true"
        
        # Pass Opik API key if provided (for cloud deployment)
        if context.opik_api_key:
            env_vars["OPIK_API_KEY"] = context.opik_api_key
        
        # Pass workspace name for SDK initialization
        env_vars["OPIK_WORKSPACE"] = context.workspace_name
        
        # Create isolated subprocess executor with Redis-backed log collection
        executor = IsolatedSubprocessExecutor(
            timeout_secs=OPTIMIZATION_TIMEOUT_SECS
        )
        
        # Create log collector for this optimization
        log_collector = create_optimization_log_collector(
            workspace_id=context.workspace_id,
            optimization_id=context.optimization_id,
        )
        
        # Store log collector in executor for subprocess log capture
        # The executor will use this to stream subprocess stdout/stderr to Redis
        executor._log_collectors[0] = log_collector  # Use 0 as placeholder PID
        
        # Define cancellation callback
        def on_cancelled() -> None:
            logger.info(
                f"Cancellation detected, killing subprocess for {context.optimization_id}"
            )
            executor.kill_all_processes(timeout=5)
        
        # Register with centralized cancellation monitor (auto-unregisters on exit)
        with CancellationHandle(str(context.optimization_id), on_cancelled=on_cancelled) as cancellation_handle:
            
            # High-level, user-facing message classified inside the subprocess (where the real
            # exception type is available). Captured here so the parent's except can surface it.
            subprocess_user_message = None

            try:
                logger.debug(f"Starting optimization subprocess for optimization {context.optimization_id}")

                # Execute optimization in isolated subprocess
                result = executor.execute(
                    file_path=OPTIMIZER_RUNNER_PATH,
                    data=job_message,
                    env_vars=env_vars,
                    payload_type=PAYLOAD_TYPE_OPTIMIZATION,
                    optimization_id=str(context.optimization_id),
                    job_id=str(context.optimization_id),
                )
                
                # Check if cancelled - don't treat as error (thread-safe check)
                if cancellation_handle.was_cancelled:
                    logger.info(f"Optimization was cancelled: {context.optimization_id}")
                    # Write cancellation message to optimization logs (visible in UI)
                    log_collector.emit({"message": "Execution cancelled by the user."})
                    cancelled: OptimizationCancelledResult = {
                        "status": "cancelled",
                        "optimization_id": str(context.optimization_id),
                    }
                    return cancelled
                
                # Check for errors (only if not cancelled)
                if "error" in result:
                    logger.error(f"Optimization failed: {result.get('error')}")
                    # Carry the subprocess's high-level, user-facing message to the except below.
                    subprocess_user_message = result.get("user_message")
                    # Surface the subprocess traceback (when present) so callers
                    # and CI see what failed inside the isolated process.
                    error_message = result.get("error", "Unknown error")
                    subprocess_traceback = result.get("traceback")
                    if subprocess_traceback:
                        logger.error(
                            "Optimization subprocess traceback:\n%s",
                            subprocess_traceback,
                        )
                        error_message = (
                            f"{error_message}\n\n"
                            f"Subprocess traceback:\n{subprocess_traceback}"
                        )
                    raise Exception(error_message)
                
                logger.info(f"Optimization completed successfully: {context.optimization_id}")
                return cast(OptimizationRunResult, result)

            except Exception as exc:
                # Cancellation is a normal terminal state, not a failure — Java already moved the run
                # to CANCELLED, so never override it with ERROR.
                if cancellation_handle.was_cancelled:
                    raise

                # Guarantee the run reaches a terminal ERROR status even when the subprocess died
                # before it could call mark_running/mark_error itself (parse/import/config/client-init
                # failures). Surface a final reason line so the UI shows why, then re-raise so RQ still
                # records the job as failed. mark_error inside the subprocess lifecycle already ran for
                # in-lifecycle failures; marking error again here is idempotent.
                logger.error(
                    "Optimization %s failed in parent worker, marking as ERROR: %s",
                    context.optimization_id,
                    exc,
                )
                try:
                    # Surface a single, high-level, user-facing line prefixed "[System]" so the UI can
                    # show WHY without the low-level detail (the full traceback is already in the logs
                    # via the subprocess's captured stdout/stderr). Prefer the message the subprocess
                    # classified from the real exception type; for parent-level failures (job parse,
                    # executor crash before the subprocess ran) classify the parent exception here.
                    user_message = subprocess_user_message or to_user_facing_message(exc)
                    log_collector.emit({"message": f"[System] {user_message}"})
                except Exception as emit_error:
                    logger.warning(f"Error emitting failure log line: {emit_error}")
                _mark_optimization_error(context)
                raise

            finally:
                # Ensure log collector is closed (flushes remaining logs)
                try:
                    log_collector.close()
                except Exception as e:
                    logger.warning(f"Error closing log collector: {e}")
