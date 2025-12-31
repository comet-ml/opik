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
from datetime import datetime
from typing import Optional

from opentelemetry import trace

from opik_backend.executor_isolated import IsolatedSubprocessExecutor
from opik_backend.subprocess_logger import (
    create_optimization_log_collector,
    RedisBatchLogCollector,
)
from opik_backend.studio import (
    LLM_API_KEYS,
    OptimizationJobContext,
    OPTIMIZATION_TIMEOUT_SECS,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


def _log_to_redis(
    log_collector: Optional[RedisBatchLogCollector],
    level: str,
    message: str,
) -> None:
    """
    Log a message to both the standard logger and Redis (if collector is available).
    
    This ensures pre-subprocess and post-subprocess messages are visible in the
    optimization logs UI, not just in the container stdout.
    """
    # Format similar to Rich console output for consistency
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    formatted_line = f"{timestamp} [{level.upper()}] {message}"
    
    # Log to standard logger
    log_level = getattr(logging, level.upper(), logging.INFO)
    logger.log(log_level, message)
    
    # Also push to Redis if collector is available
    if log_collector:
        try:
            log_collector.add_log_line(formatted_line)
        except Exception as e:
            logger.warning(f"Failed to push log to Redis: {e}")

# Path to the optimizer runner script
OPTIMIZER_RUNNER_PATH = os.path.join(
    os.path.dirname(__file__),
    "optimizer_runner.py"
)

# Payload type constant for optimization jobs
PAYLOAD_TYPE_OPTIMIZATION = "optimization"


class JobMessageParseError(Exception):
    """Raised when job message cannot be parsed."""
    pass


def _parse_job_message(args, kwargs):
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


def process_optimizer_job(*args, **kwargs):
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
    log_collector = None
    
    with tracer.start_as_current_span("process_optimizer_job") as span:
        try:
            # Parse job message first (don't log raw args/kwargs - they contain API keys)
            job_message = _parse_job_message(args, kwargs)
            context = OptimizationJobContext.from_job_message(job_message)
            
            # Set span attributes for tracing
            span.set_attribute("optimization_id", str(context.optimization_id))
            span.set_attribute("workspace_id", context.workspace_id)
            span.set_attribute("workspace_name", context.workspace_name)
            
            # Create log collector early so we can capture pre-subprocess logs
            log_collector = create_optimization_log_collector(
                workspace_id=context.workspace_id,
                optimization_id=context.optimization_id,
            )
            
            _log_to_redis(
                log_collector, "info",
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
            executor = IsolatedSubprocessExecutor(
                timeout_secs=OPTIMIZATION_TIMEOUT_SECS
            )
            
            # Store log collector in executor for subprocess log capture
            # The executor will use this to stream subprocess stdout/stderr to Redis
            executor._log_collectors[0] = log_collector  # Use 0 as placeholder PID
            
            _log_to_redis(
                log_collector, "info",
                f"Starting optimization subprocess for optimization {context.optimization_id}"
            )
            
            # Execute optimization in isolated subprocess
            result = executor.execute(
                file_path=OPTIMIZER_RUNNER_PATH,
                data=job_message,
                env_vars=env_vars,
                payload_type=PAYLOAD_TYPE_OPTIMIZATION,
                optimization_id=str(context.optimization_id),
                job_id=str(context.optimization_id),
            )
            
            # Check for errors
            if "error" in result:
                _log_to_redis(
                    log_collector, "error",
                    f"Optimization failed: {result.get('error')}"
                )
                raise Exception(result.get("error", "Unknown error"))
            
            _log_to_redis(
                log_collector, "info",
                f"Optimization completed successfully: {context.optimization_id}"
            )
            return result
        
        except Exception as e:
            # Log any exception to Redis before re-raising
            _log_to_redis(
                log_collector, "error",
                f"Optimization error: {str(e)}"
            )
            raise
        
        finally:
            # Ensure log collector is closed (flushes remaining logs)
            if log_collector:
                try:
                    log_collector.close()
                except Exception as e:
                    logger.warning(f"Error closing log collector: {e}")
