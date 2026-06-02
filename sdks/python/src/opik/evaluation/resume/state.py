"""
Resume state persisted in ``experiment_config[_opik_resume]``.

The schema is modelled as a sum type:

- :class:`ResumableState` — the experiment **can** be resumed; carries all
  configuration needed to rebuild iteration deterministically.
- :class:`NonResumableState` — the experiment **cannot** be resumed; carries
  the human-facing reason.

This module owns the encoding (:func:`embed_resumable_state` /
:func:`embed_non_resumable_state`) and decoding (:func:`read_resume_state`).
The schema deliberately stores only small, reproducible configuration —
never resolved data lists. When iteration cannot be rebuilt from config
alone (custom sampler or explicit ``dataset_item_ids``), a
``requires_local_checkpoint`` flag points the resume flow at the companion
checkpoint file (see :mod:`opik.evaluation.resume.checkpoint`).

Neither dataclass nor encoding function carries default values — callers
must be explicit. Defaults belong on user-facing APIs (e.g.
``evaluate_resume``), not on internal persistence boundaries where an
omitted field would silently produce a corrupt blob.
"""

import dataclasses
import json
import logging
from typing import Any, Dict, Optional, Union

from ...api_objects.experiment import experiment as experiment_module


LOGGER = logging.getLogger(__name__)

RESUME_METADATA_KEY = "_opik_resume"
RESUME_SCHEMA_VERSION = 1


@dataclasses.dataclass(frozen=True)
class ResumableState:
    """
    Full configuration needed to rebuild iteration on resume.

    ``dataset_version_name`` is non-optional: resume only operates against a
    pinned :class:`DatasetVersion`, never a moving ``Dataset`` HEAD.

    ``requires_completion_marker`` tells ``evaluate_resume`` that this
    experiment was written by an SDK that flips a marker on the trace at
    happy-path completion. If the backend's experiment-item compare
    response doesn't surface that field at all (older BE missing the
    projection from OPIK-5269), resume bails out via
    :class:`BackendTooOldForResume` rather than silently replaying every
    item.
    """

    default_runs_per_item: int
    dataset_filter_string: Optional[str]
    dataset_version_name: str
    nb_samples: Optional[int]
    requires_local_checkpoint: bool
    requires_completion_marker: bool


@dataclasses.dataclass(frozen=True)
class NonResumableState:
    """Marker that the experiment cannot be safely resumed."""

    reason: str


PersistedResumeState = Union[ResumableState, NonResumableState]


def embed_resumable_state(
    experiment_config: Optional[Dict[str, Any]],
    state: ResumableState,
) -> Dict[str, Any]:
    """
    Embed a :class:`ResumableState` blob into ``experiment_config``.

    The blob is stored as a single JSON-string value under
    ``RESUME_METADATA_KEY`` so the experiment Configuration UI shows one
    row instead of one row per nested field.
    """
    new_config = dict(experiment_config) if experiment_config else {}
    new_config[RESUME_METADATA_KEY] = json.dumps(
        {
            "schema_version": RESUME_SCHEMA_VERSION,
            "resumable": True,
            "default_runs_per_item": state.default_runs_per_item,
            "dataset_filter_string": state.dataset_filter_string,
            "dataset_version_name": state.dataset_version_name,
            "nb_samples": state.nb_samples,
            "requires_local_checkpoint": state.requires_local_checkpoint,
            "requires_completion_marker": state.requires_completion_marker,
        }
    )
    return new_config


def embed_non_resumable_state(
    experiment_config: Optional[Dict[str, Any]],
    state: NonResumableState,
) -> Dict[str, Any]:
    """
    Embed a non-resumable marker into ``experiment_config``.

    Only the marker + reason are stored; no iteration configs leak through.
    Serialized as a JSON string for the same UI-display reason as
    :func:`embed_resumable_state`.
    """
    new_config = dict(experiment_config) if experiment_config else {}
    new_config[RESUME_METADATA_KEY] = json.dumps(
        {
            "schema_version": RESUME_SCHEMA_VERSION,
            "resumable": False,
            "non_resumable_reason": state.reason,
        }
    )
    return new_config


def read_resume_state(
    experiment: experiment_module.Experiment,
) -> Optional[PersistedResumeState]:
    """
    Decode the resume blob attached to the experiment.

    Returns:
        * :class:`ResumableState` when the blob marks the experiment resumable
          and carries a pinned dataset version.
        * :class:`NonResumableState` when the blob marks the experiment
          non-resumable.
        * ``None`` when no blob is present (e.g. created by an older SDK
          version, or by an external client) — callers must treat this as
          unresumable and surface a clear error.

    A resumable blob with no pinned ``dataset_version_name`` is downgraded
    to :class:`NonResumableState`. Iteration against a moving dataset HEAD
    would break the resume contract; rather than silently allowing it, this
    function refuses at the decode boundary.
    """
    raw_state = _read_raw_resume_state(experiment)
    if raw_state is None:
        return None

    if not raw_state.get("resumable", False):
        return NonResumableState(
            reason=_coerce_optional_str(raw_state.get("non_resumable_reason"))
            or "unspecified"
        )

    dataset_version_name = _coerce_optional_str(
        raw_state.get("dataset_version_name")
    )
    if dataset_version_name is None:
        return NonResumableState(
            reason=(
                "resume blob is missing the pinned dataset_version_name; "
                "the experiment cannot be safely resumed"
            )
        )

    return ResumableState(
        default_runs_per_item=_coerce_positive_int(
            raw_state.get("default_runs_per_item"), fallback=1
        ),
        dataset_filter_string=_coerce_optional_str(
            raw_state.get("dataset_filter_string")
        ),
        dataset_version_name=dataset_version_name,
        nb_samples=_coerce_optional_positive_int(raw_state.get("nb_samples")),
        requires_local_checkpoint=bool(
            raw_state.get("requires_local_checkpoint", False)
        ),
        requires_completion_marker=bool(
            raw_state.get("requires_completion_marker", False)
        ),
    )


def _read_raw_resume_state(
    experiment: experiment_module.Experiment,
) -> Optional[Dict[str, Any]]:
    """
    Decode the raw resume blob from the experiment metadata.

    The blob is always a JSON-encoded string under ``RESUME_METADATA_KEY``.
    Any other shape (missing, non-string, malformed JSON) is treated as
    "no resume state" so callers raise the appropriate error.
    """
    experiment_data = experiment.get_experiment_data()
    metadata = getattr(experiment_data, "metadata", None) or {}
    if not isinstance(metadata, dict):
        return None

    raw = metadata.get(RESUME_METADATA_KEY)
    if not isinstance(raw, str):
        return None

    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError:
        LOGGER.warning(
            "Failed to decode JSON resume state on experiment metadata; "
            "treating the experiment as having no resume state.",
            exc_info=True,
        )
        return None

    return decoded if isinstance(decoded, dict) else None


def _coerce_positive_int(value: Any, *, fallback: int) -> int:
    if isinstance(value, int) and value >= 1:
        return value
    return fallback


def _coerce_optional_positive_int(value: Any) -> Optional[int]:
    if isinstance(value, int) and value >= 1:
        return value
    return None


def _coerce_optional_str(value: Any) -> Optional[str]:
    if isinstance(value, str):
        return value
    return None
