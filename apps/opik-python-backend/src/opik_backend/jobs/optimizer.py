"""
Optimizer job processor for Optimization Studio.

This module handles optimization jobs from the Java backend via Redis Queue (RQ).
Each optimization runs in an isolated subprocess for:
- Customer isolation (separate SDK clients, API keys)
- Memory isolation
- Crash isolation (one optimization failing doesn't affect others)

Logs from the subprocess are captured and streamed to Redis for S3 sync.

The optimization code is generated from the configuration and executed in a temporary file.
"""

import logging
import os
import tempfile
from pathlib import Path
from typing import Any, Dict, Tuple

from opentelemetry import trace

from opik_backend.executor_isolated import IsolatedSubprocessExecutor
from opik_backend.subprocess_logger import create_optimization_log_collector
from opik_backend.studio import (
    LLM_API_KEYS,
    OptimizationJobContext,
    OptimizationConfig,
    OPTIMIZATION_TIMEOUT_SECS,
    CancellationHandle,
    JobMessageParseError,
    OptimizationCodeGenerator,
    OptimizationError,
    InvalidOptimizerError,
    InvalidMetricError,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# Payload type constant for optimization jobs
PAYLOAD_TYPE_OPTIMIZATION = "optimization"


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


def process_optimizer_job(*args: Any, **kwargs: Any) -> Dict[str, Any]:
    """Process an optimizer job from the Java backend.

    This is the main entry point for Optimization Studio jobs. It:
    1. Parses the job message
    2. Generates Python code from the configuration
    3. Writes the generated code to a temporary file
    4. Creates an isolated subprocess executor
    5. Sets up log collection (Redis-backed)
    6. Runs the optimization in the subprocess using the generated code
    7. Returns the result

    The optimization code is generated from the configuration using OptimizationCodeGenerator.
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

        logger.info(
            f"Processing Optimization Studio job: {context.optimization_id} "
            f"for workspace: {context.workspace_name}"
        )

        # Prepare environment variables for subprocess
        # Pass LLM API keys and Opik configuration
        env_vars = {
            **LLM_API_KEYS,  # OPENAI_API_KEY, ANTHROPIC_API_KEY, etc.
        }

        # Pass Opik API key if provided (for cloud deployment)
        if context.opik_api_key:
            env_vars["OPIK_API_KEY"] = context.opik_api_key

        # Pass workspace name for SDK initialization
        env_vars["OPIK_WORKSPACE"] = context.workspace_name

        # Create isolated subprocess executor with Redis-backed log collection
        executor = IsolatedSubprocessExecutor(timeout_secs=OPTIMIZATION_TIMEOUT_SECS)

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
        with CancellationHandle(
            str(context.optimization_id), on_cancelled=on_cancelled
        ) as cancellation_handle:

            # Parse config for code generation and generate code
            # Wrap in try/except to catch validation errors before optimization starts
            try:
                config = OptimizationConfig.from_dict(job_message.get("config", {}))

                # Generate Python code from configuration
                logger.info(
                    f"Generating optimization code for {context.optimization_id}"
                )
                generated_code = OptimizationCodeGenerator.generate(config, context)
            except (OptimizationError, KeyError, ValueError) as e:
                # Configuration validation failed - log error and re-raise to trigger RQ failure
                # This ensures the Java backend sees the job as failed and updates status/metrics
                error_msg = str(e)
                logger.error(
                    f"Configuration validation failed for {context.optimization_id}: {error_msg}"
                )
                log_collector.emit(
                    {"message": f"Configuration validation failed: {error_msg}"}
                )

                # Ensure log collector is closed before re-raising
                try:
                    log_collector.close()
                except Exception as close_error:
                    logger.warning(
                        f"Error closing log collector after validation failure: {close_error}"
                    )

                # Re-raise exception so RQ marks job as failed
                # This allows Java backend to detect failure and update optimization status/metrics
                # Cancellation handle will auto-unregister on exit
                raise

            # Write generated code to temporary file
            temp_file = None
            try:
                with tempfile.NamedTemporaryFile(
                    mode="w", suffix=".py", delete=False
                ) as f:
                    f.write(generated_code)
                    temp_file = f.name

                logger.debug(f"Generated code written to temporary file: {temp_file}")

                logger.info(
                    f"Starting optimization subprocess for optimization {context.optimization_id}"
                )

                # Execute optimization in isolated subprocess using generated code
                result = executor.execute(
                    file_path=temp_file,
                    data=job_message,
                    env_vars=env_vars,
                    payload_type=PAYLOAD_TYPE_OPTIMIZATION,
                    optimization_id=str(context.optimization_id),
                    job_id=str(context.optimization_id),
                )

                # Check if cancelled - don't treat as error (thread-safe check)
                if cancellation_handle.was_cancelled:
                    logger.info(
                        f"Optimization was cancelled: {context.optimization_id}"
                    )
                    # Write cancellation message to optimization logs (visible in UI)
                    log_collector.emit({"message": "Execution cancelled by the user."})
                    return {
                        "status": "cancelled",
                        "optimization_id": str(context.optimization_id),
                    }

                # Check for errors (only if not cancelled)
                if "error" in result:
                    logger.error(f"Optimization failed: {result.get('error')}")
                    raise Exception(result.get("error", "Unknown error"))

                logger.info(
                    f"Optimization completed successfully: {context.optimization_id}"
                )
                return result

            finally:
                # Clean up temporary file
                if temp_file and Path(temp_file).exists():
                    try:
                        Path(temp_file).unlink()
                        logger.debug(f"Cleaned up temporary file: {temp_file}")
                    except Exception as e:
                        logger.warning(
                            f"Error cleaning up temporary file {temp_file}: {e}"
                        )

                # Ensure log collector is closed (flushes remaining logs)
                try:
                    log_collector.close()
                except Exception as e:
                    logger.warning(f"Error closing log collector: {e}")
