"""
Resume support for evaluations.

Public surface is intentionally small. Callers that want to resume an
experiment use :func:`prepare_resume_context` to obtain a
:class:`ResumeContext`, then pass it through :func:`build_pending_items_iterator`
on their item stream. The evaluator wiring (embedding resume state at
evaluation time, writing the local checkpoint when needed) lives in
:mod:`integration` and is called from the evaluation entrypoints.
"""

from .checkpoint import (
    LOCAL_CHECKPOINT_DIR,
    checkpoint_path,
    delete_checkpoint,
    read_checkpoint,
    write_checkpoint,
)
from ...exceptions import ExperimentNotResumable, LocalCheckpointMissing
from .context import ResumeContext, prepare_resume_context
from .iteration import (
    build_pending_items_iterator,
    expected_runs_for_item,
    remaining_runs_for_item,
)
from .state import (
    RESUME_METADATA_KEY,
    RESUME_SCHEMA_VERSION,
    NonResumableState,
    PersistedResumeState,
    ResumableState,
    embed_non_resumable_state,
    embed_resumable_state,
    read_resume_state,
)

__all__ = [
    "ExperimentNotResumable",
    "LocalCheckpointMissing",
    "LOCAL_CHECKPOINT_DIR",
    "NonResumableState",
    "PersistedResumeState",
    "RESUME_METADATA_KEY",
    "RESUME_SCHEMA_VERSION",
    "ResumableState",
    "ResumeContext",
    "build_pending_items_iterator",
    "checkpoint_path",
    "delete_checkpoint",
    "embed_non_resumable_state",
    "embed_resumable_state",
    "expected_runs_for_item",
    "prepare_resume_context",
    "read_checkpoint",
    "read_resume_state",
    "remaining_runs_for_item",
    "write_checkpoint",
]
