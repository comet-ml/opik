"""
Framework optimizer job processor for the new optimization framework.

This module handles optimization jobs from the Java backend via Redis Queue (RQ)
for the new optimizer framework (opik_optimizer_framework). It uses the shared
optimizer_job_helper for the common execution logic.
"""

import os
from typing import Any, Dict

from opik_backend.jobs.optimizer_job_helper import run_optimizer_job

FRAMEWORK_RUNNER_PATH = os.path.join(
    os.path.dirname(__file__),
    "framework_runner.py"
)


def process_framework_optimizer_job(*args: Any, **kwargs: Any) -> Dict[str, Any]:
    """Process a framework optimizer job from the Java backend.

    Routes to the new framework runner (framework_runner.py) via the shared
    optimizer job execution logic.
    """
    return run_optimizer_job(
        span_name="process_framework_optimizer_job",
        runner_path=FRAMEWORK_RUNNER_PATH,
        args=args,
        kwargs=kwargs,
    )
