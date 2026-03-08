"""
Shared helper for optimizer job processors.

Provides the common execution logic used by both the legacy optimizer
(optimizer.py) and the new framework optimizer (framework_optimizer.py).
"""

import logging
from typing import Any, Dict, Tuple

from opentelemetry import trace

from opik_backend.executor_isolated import IsolatedSubprocessExecutor
from opik_backend.subprocess_logger import create_optimization_log_collector
from opik_backend.studio import (
    LLM_API_KEYS,
    OptimizationJobContext,
    OPTIMIZATION_TIMEOUT_SECS,
    CancellationHandle,
    JobMessageParseError,
)

logger = logging.getLogger(__name__)

PAYLOAD_TYPE_OPTIMIZATION = "optimization"


def parse_job_message(args: Tuple[Any, ...], kwargs: Dict[str, Any]) -> Dict[str, Any]:
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


def run_optimizer_job(
    span_name: str,
    runner_path: str,
    args: Tuple[Any, ...],
    kwargs: Dict[str, Any],
) -> Dict[str, Any]:
    """Execute an optimizer job in an isolated subprocess.

    Args:
        span_name: Name for the OpenTelemetry span
        runner_path: Path to the runner script to execute
        args: Job arguments
        kwargs: Job keyword arguments

    Returns:
        Dictionary with optimization results
    """
    tracer = trace.get_tracer(__name__)

    with tracer.start_as_current_span(span_name) as span:
        job_message = parse_job_message(args, kwargs)
        context = OptimizationJobContext.from_job_message(job_message)

        span.set_attribute("optimization_id", str(context.optimization_id))
        span.set_attribute("workspace_id", context.workspace_id)
        span.set_attribute("workspace_name", context.workspace_name)

        logger.debug(
            f"Processing {span_name}: {context.optimization_id} "
            f"for workspace: {context.workspace_name}"
        )

        env_vars = {
            **LLM_API_KEYS,
        }

        env_vars["OPIK_OPTIMIZATION_STUDIO"] = "true"

        if context.opik_api_key:
            env_vars["OPIK_API_KEY"] = context.opik_api_key

        env_vars["OPIK_WORKSPACE"] = context.workspace_name

        executor = IsolatedSubprocessExecutor(
            timeout_secs=OPTIMIZATION_TIMEOUT_SECS
        )

        log_collector = create_optimization_log_collector(
            workspace_id=context.workspace_id,
            optimization_id=context.optimization_id,
        )

        executor._log_collectors[0] = log_collector

        def on_cancelled() -> None:
            logger.info(
                f"Cancellation detected, killing subprocess for {context.optimization_id}"
            )
            executor.kill_all_processes(timeout=5)

        with CancellationHandle(str(context.optimization_id), on_cancelled=on_cancelled) as cancellation_handle:
            try:
                logger.debug(f"Starting subprocess for {context.optimization_id}")

                result = executor.execute(
                    file_path=runner_path,
                    data=job_message,
                    env_vars=env_vars,
                    payload_type=PAYLOAD_TYPE_OPTIMIZATION,
                    optimization_id=str(context.optimization_id),
                    job_id=str(context.optimization_id),
                )

                if cancellation_handle.was_cancelled:
                    logger.info(f"Optimization was cancelled: {context.optimization_id}")
                    log_collector.emit({"message": "Execution cancelled by the user."})
                    return {"status": "cancelled", "optimization_id": str(context.optimization_id)}

                if "error" in result:
                    logger.error(f"Optimization failed: {result.get('error')}")
                    raise Exception(result.get("error", "Unknown error"))

                logger.info(f"Optimization completed successfully: {context.optimization_id}")
                return result

            finally:
                try:
                    log_collector.close()
                except Exception as e:
                    logger.warning(f"Error closing log collector: {e}")
