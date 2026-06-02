"""
Internal contract between ``evaluate()`` (which writes a marker on the
trace when a trial completes its full happy path) and
``evaluate_resume()`` (which reads the marker to decide whether the
trial needs to be replayed).

This module is the **only** place in the SDK that knows where the
marker lives and what its values look like. Callers in
``evaluation.engine`` and ``evaluation.resume`` go through the
functions exposed here; they do not import the underlying key or
sentinel values directly. To change the marker design (different key,
different storage field, different sentinel shape), edit this module —
its callers should not need to learn the new shape.
"""

from typing import Any, Dict, List

from .. import exceptions as opik_exceptions
from ..api_objects.experiment import experiment_item

_PENDING_KEY = "_opik_evaluation_pending"


def initial_metadata() -> Dict[str, Any]:
    """Marker seed for a trace built by the evaluation engine.

    Returned dict is passed to ``trace.TraceData(metadata=...)`` at
    trace creation. Resume treats any trial whose trace still carries
    this default as "did not finish".
    """
    return {_PENDING_KEY: True}


def completed_metadata() -> Dict[str, Any]:
    """Marker mutation for the engine's happy-path-only line.

    Returned dict is passed to ``opik_context.update_current_trace(
    metadata=...)`` right after task + scoring + score-logging all
    returned. Anything that prevents control flow from reaching that
    call leaves the marker at its initial value, which resume reads as
    "trial incomplete, replay it".
    """
    return {_PENDING_KEY: False}


def is_trial_fully_completed(
    item: experiment_item.ExperimentItemContent,
) -> bool:
    """True iff the trial reached the engine's happy-path-only line."""
    metadata = item.trace_metadata or {}
    return metadata.get(_PENDING_KEY) is False


def ensure_backend_supports_marker(
    *,
    experiment_id: str,
    experiment_items: List[experiment_item.ExperimentItemContent],
) -> None:
    """Raise when the connected BE doesn't surface the marker at all.

    The marker is seeded on every trace at creation, so an empty
    ``trace_metadata`` across every item means the backend is missing
    the ``ExperimentItem.traceMetadata`` projection (older than
    OPIK-5269). Without it, resume can't tell which trials reached the
    happy-path line and would incorrectly replay every item.
    """
    if not experiment_items:
        return
    if any(item.trace_metadata for item in experiment_items):
        return
    raise opik_exceptions.BackendTooOldForResume(
        f"Experiment {experiment_id} was created with a SDK that writes the "
        "trace-level resume marker, but the connected backend does not "
        "surface ``trace_metadata`` on the experiment-item compare endpoint. "
        "Upgrade the backend to a version that includes the OPIK-5269 "
        "``ExperimentItem.traceMetadata`` projection, or downgrade the SDK "
        "to a version that does not depend on it."
    )
