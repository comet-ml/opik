"""
Optimizer job processor for Optimization Studio.

This module handles optimization jobs from the Java backend via Redis Queue (RQ).
Each optimization runs in an isolated subprocess for:
- Customer isolation (separate SDK clients, API keys)
- Memory isolation
- Crash isolation (one optimization failing doesn't affect others)

Logs from the subprocess are captured and streamed to Redis for S3 sync.
"""

import os
from typing import Any, Dict

from opik_backend.jobs.optimizer_job_helper import run_optimizer_job

# Path to the optimizer runner script
OPTIMIZER_RUNNER_PATH = os.path.join(
    os.path.dirname(__file__),
    "optimizer_runner.py"
)


def process_optimizer_job(*args: Any, **kwargs: Any) -> Dict[str, Any]:
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
    return run_optimizer_job(
        span_name="process_optimizer_job",
        runner_path=OPTIMIZER_RUNNER_PATH,
        args=args,
        kwargs=kwargs,
    )
