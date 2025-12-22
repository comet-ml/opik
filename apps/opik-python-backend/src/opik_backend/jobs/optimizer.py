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

from opentelemetry import trace

from opik_backend.executor_isolated import IsolatedSubprocessExecutor
from opik_backend.subprocess_logger import create_optimization_log_collector
from opik_backend.studio import (
    LLM_API_KEYS,
    OptimizationJobContext,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# Path to the optimizer runner script
OPTIMIZER_RUNNER_PATH = os.path.join(
    os.path.dirname(__file__),
    "optimizer_runner.py"
)

# Default timeout for optimization (2 hours)
DEFAULT_OPTIMIZATION_TIMEOUT_SECS = 7200


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
    with tracer.start_as_current_span("process_optimizer_job") as span:
        logger.info(f"Received optimizer job - args: {args}, kwargs: {kwargs}")
        
        # Parse job message
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
        executor = IsolatedSubprocessExecutor(
            timeout_secs=DEFAULT_OPTIMIZATION_TIMEOUT_SECS
        )
        
        # Create log collector for this optimization
        log_collector = create_optimization_log_collector(
            workspace_id=context.workspace_id,
            optimization_id=context.optimization_id,
        )
        
        # Store log collector in executor for subprocess log capture
        # The executor will use this to stream subprocess stdout/stderr to Redis
        executor._log_collectors[0] = log_collector  # Use 0 as placeholder PID
        
        try:
            logger.info(f"Starting optimization subprocess for optimization {context.optimization_id}")
            
            # Execute optimization in isolated subprocess
            result = executor.execute(
                file_path=OPTIMIZER_RUNNER_PATH,
                data=job_message,
                env_vars=env_vars,
                timeout_secs=DEFAULT_OPTIMIZATION_TIMEOUT_SECS,
                payload_type="optimization",
                optimization_id=str(context.optimization_id),
                job_id=str(context.optimization_id),
            )
            
            # Check for errors
            if "error" in result:
                logger.error(f"Optimization failed: {result.get('error')}")
                raise Exception(result.get("error", "Unknown error"))
            
            logger.info(f"Optimization completed successfully: {context.optimization_id}")
            return result
        
        finally:
            # Ensure log collector is closed (flushes remaining logs)
            try:
                log_collector.close()
            except Exception as e:
                logger.warning(f"Error closing log collector: {e}")
