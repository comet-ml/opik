"""E2E regression test for OPIK-7511: the run's stop cause is persisted.

Drives the real entrypoint (``process_optimizer_job`` → ``optimizer_runner.py``
subprocess → gateway-routed LLM calls) and asserts the new authoritative
signal: ``finish_reason`` is returned in the subprocess result AND persisted on
the optimization record as ``metadata.finish_reason``, so the UI (OPIK_7458)
can render the real stop cause instead of a heuristic. Before this, a run that
produced no candidates was indistinguishable from a metric failure.
"""

import os
from typing import Any

import pytest

import opik
from opik import synchronization

from llm_constants import ANTHROPIC_CLAUDE_HAIKU

pytestmark = pytest.mark.e2e

# CI uses the workspace Anthropic key; overridable for local stacks whose
# workspace has a different provider configured.
_MODEL = os.getenv("OPTSTUDIO_E2E_MODEL", ANTHROPIC_CLAUDE_HAIKU)

# Mirrors the SDK's FinishReason literal / the backend's KNOWN_FINISH_REASONS.
_ALLOWED_FINISH_REASONS = {
    "completed",
    "perfect_score",
    "max_trials",
    "no_improvement",
    "error",
    "cancelled",
}


def _metadata_field(optimization: Any, name: str) -> Any:
    """Read a metadata key off the fetched optimization, tolerating both the
    pinned SDK (plain dict) and a newer typed object."""
    metadata = getattr(optimization, "metadata", None)
    if isinstance(metadata, dict):
        return metadata.get(name)
    return getattr(metadata, name, None)


def _wait_for_completed_with_metadata(
    opik_client: opik.Opik, optimization_id: str
) -> Any:
    """Poll until the run is completed AND metadata.finish_reason is visible.

    The optimization row is a ClickHouse ReplacingMergeTree versioned
    re-insert, so the enriched completion update isn't guaranteed to be
    readable the instant the subprocess exits.
    """
    fetched: dict[str, Any] = {}

    def _ready() -> bool:
        optimization = opik_client.rest_client.optimizations.get_optimization_by_id(
            optimization_id
        )
        fetched["optimization"] = optimization
        return optimization.status == "completed" and bool(
            _metadata_field(optimization, "finish_reason")
        )

    assert synchronization.until(_ready, sleep=1.0, max_try_seconds=60), (
        f"optimization {optimization_id} never reached completed status with "
        f"metadata.finish_reason (last: status="
        f"{getattr(fetched.get('optimization'), 'status', None)!r}, metadata="
        f"{getattr(fetched.get('optimization'), 'metadata', None)!r})"
    )
    return fetched["optimization"]


def test_finish_reason_is_returned_and_persisted(
    opik_client: opik.Opik,
    anthropic_workspace_key: None,
    project_name: str,
    seeded_sentiment_classification_dataset: opik.Dataset,
    run_studio_optimization: Any,
) -> None:
    """A GEPA studio run ends with an allowlisted finish_reason in both the
    subprocess result and the persisted optimization metadata (OPIK-7511).

    The clear-cut sentiment dataset makes a strong baseline likely, which is
    exactly the dogfooding case that used to end silently: either the run now
    attempts candidates, or it stops with a recorded reason — never neither.
    """
    dataset_name = seeded_sentiment_classification_dataset.name
    studio_config = {
        "dataset_name": dataset_name,
        "prompt": {
            "messages": [
                {
                    "role": "user",
                    "content": 'Classify the sentiment of this movie review as '
                    'exactly "positive" or "negative": {{text}}',
                }
            ]
        },
        "llm_model": {"model": _MODEL, "parameters": {}},
        "evaluation": {
            "metrics": [
                {
                    "type": "equals",
                    "parameters": {"reference_key": "label", "case_sensitive": False},
                }
            ]
        },
        "optimizer": {"type": "gepa", "parameters": {"seed": 42}},
    }

    result = run_studio_optimization(project_name, dataset_name, studio_config)

    # The subprocess result carries the stop cause (optimizer_runner output).
    finish_reason = result.get("finish_reason")
    assert finish_reason in _ALLOWED_FINISH_REASONS, (
        f"subprocess result carries no valid finish_reason: {finish_reason!r}"
    )

    # ... and the same value is persisted where the frontend reads it.
    optimization = _wait_for_completed_with_metadata(
        opik_client, run_studio_optimization.last_optimization_id
    )
    persisted = _metadata_field(optimization, "finish_reason")
    assert persisted == finish_reason, (
        f"metadata.finish_reason ({persisted!r}) does not match the subprocess "
        f"result ({finish_reason!r})"
    )

    # scoring_health rides the same completion update; it must not be lost now
    # that the metadata payload carries both keys.
    scoring_health = _metadata_field(optimization, "scoring_health")
    assert isinstance(scoring_health, dict) and "failed_count" in scoring_health, (
        f"scoring_health missing from completion metadata: {scoring_health!r}"
    )
