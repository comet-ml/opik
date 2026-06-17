"""Shared helpers for the Optimization Studio e2e tests.

Both e2e tests drive the **real entrypoint** (``process_optimizer_job``) with a
provider key stored in the workspace (resolved server-side via the gateway),
never a key handed to the optimizer. These helpers capture that shared shape.
"""

import os
from typing import Any

import opik

from opik_backend.jobs.optimizer import process_optimizer_job


def run_studio_job(
    opik_client: opik.Opik,
    project_name: str,
    dataset_name: str,
    studio_config: dict[str, Any],
) -> dict[str, Any]:
    """Pre-create the optimization record (as the Java backend would), then run
    the real job handler. Returns the result dict from the subprocess."""
    workspace = os.getenv("OPIK_WORKSPACE", "default")
    optimization = opik_client.create_optimization(
        dataset_name=dataset_name,
        objective_name=studio_config["evaluation"]["metrics"][0]["type"],
        project_name=project_name,
    )
    job_message = {
        "optimization_id": optimization.id,
        "workspace_id": workspace,
        "workspace_name": workspace,
        "config": studio_config,
        "project_name": project_name,
    }
    return process_optimizer_job(job_message)


def assert_optimization_healthy(result: dict[str, Any]) -> None:
    """Signals that the optimization actually ran end-to-end."""
    assert result is not None, "no result returned"
    assert "error" not in result, f"optimization errored: {result.get('error')}"
    assert result.get("status") != "cancelled", "optimization was cancelled"
    # Baseline established + a score produced, both in range.
    assert result.get("initial_score") is not None, "no baseline score (it didn't establish a baseline)"
    assert 0.0 <= result["initial_score"] <= 1.0, f"baseline {result['initial_score']} out of range"
    assert result.get("score") is not None, "no final score"
    assert 0.0 <= result["score"] <= 1.0, f"score {result['score']} out of range"
    # Optimization shouldn't make the prompt worse than the baseline.
    assert result["score"] >= result["initial_score"], (
        f"optimized score {result['score']} regressed below baseline {result['initial_score']}"
    )
    # An optimized prompt was produced.
    assert result.get("optimized_prompt"), "no optimized prompt produced"
