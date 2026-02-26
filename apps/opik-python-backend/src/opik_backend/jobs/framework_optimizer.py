"""
Framework optimizer job processor for the new optimization framework.

This module handles optimization jobs from the Java backend via Redis Queue (RQ)
for the new optimizer framework (opik_optimizer_framework). It follows the same
pattern as optimizer.py but routes to framework_runner.py.
"""

import logging
import os
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
tracer = trace.get_tracer(__name__)

FRAMEWORK_RUNNER_PATH = os.path.join(
    os.path.dirname(__file__),
    "framework_runner.py"
)

PAYLOAD_TYPE_OPTIMIZATION = "optimization"


def _parse_job_message(args: Tuple[Any, ...], kwargs: Dict[str, Any]) -> Dict[str, Any]:
    if args and isinstance(args[0], dict):
        return args[0]
    if kwargs:
        return kwargs
    raise JobMessageParseError("No job message found in args or kwargs")


def process_framework_optimizer_job(*args: Any, **kwargs: Any) -> Dict[str, Any]:
    """Process a framework optimizer job from the Java backend.

    Same pattern as process_optimizer_job but routes to the new framework runner.
    """
    with tracer.start_as_current_span("process_framework_optimizer_job") as span:
        job_message = _parse_job_message(args, kwargs)
        context = OptimizationJobContext.from_job_message(job_message)

        span.set_attribute("optimization_id", str(context.optimization_id))
        span.set_attribute("workspace_id", context.workspace_id)
        span.set_attribute("workspace_name", context.workspace_name)

        logger.debug(
            f"Processing framework optimization job: {context.optimization_id} "
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
                logger.debug(f"Starting framework optimization subprocess for {context.optimization_id}")

                result = executor.execute(
                    file_path=FRAMEWORK_RUNNER_PATH,
                    data=job_message,
                    env_vars=env_vars,
                    payload_type=PAYLOAD_TYPE_OPTIMIZATION,
                    optimization_id=str(context.optimization_id),
                    job_id=str(context.optimization_id),
                )

                if cancellation_handle.was_cancelled:
                    logger.info(f"Framework optimization was cancelled: {context.optimization_id}")
                    log_collector.emit({"message": "Execution cancelled by the user."})
                    return {"status": "cancelled", "optimization_id": str(context.optimization_id)}

                if "error" in result:
                    logger.error(f"Framework optimization failed: {result.get('error')}")
                    raise Exception(result.get("error", "Unknown error"))

                logger.info(f"Framework optimization completed successfully: {context.optimization_id}")
                return result

            finally:
                try:
                    log_collector.close()
                except Exception as e:
                    logger.warning(f"Error closing log collector: {e}")
