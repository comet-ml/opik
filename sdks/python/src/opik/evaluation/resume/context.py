"""
The orchestrator that turns a stored experiment into a live ``ResumeContext``.

Reads the persisted state (:mod:`opik.evaluation.resume.state`), optionally
loads a checkpoint of resolved item ids (default reader pulls from
:mod:`opik.evaluation.resume.checkpoint`, injectable), resolves the dataset
**at its pinned version**, and counts the completed runs per dataset item
from the experiment's existing items.

A resume is always bound to a specific :class:`DatasetVersion`. If the
experiment record has no pinned ``dataset_version_name`` (because it was
created before resume support, by an external client, or against a dataset
with versioning disabled), this module refuses to build a context and
raises :class:`ExperimentNotResumable`. Iteration against a moving
``Dataset`` HEAD would silently include / exclude items added or removed
since the original run, which breaks the resume contract.

The checkpoint reader is injected so this module does not hard-depend on
the local-file implementation: a future server-side artifact store can
plug in without touching this orchestrator.
"""

import dataclasses
import logging
from typing import Callable, Dict, List, Mapping, Optional

from ... import exceptions as opik_exceptions
from ...api_objects import opik_client
from ...api_objects.dataset import dataset
from ...api_objects.experiment import experiment as experiment_module
from ...api_objects.experiment import experiment_item
from ..engine import helpers as engine_helpers
from . import checkpoint as checkpoint_module
from . import state as state_module

LOGGER = logging.getLogger(__name__)


CheckpointReader = Callable[[str], Optional[List[str]]]


@dataclasses.dataclass(frozen=True)
class ResumeContext:
    """Everything required to continue an interrupted experiment.

    ``dataset`` is always a :class:`DatasetVersion` pinned to the version
    the original ``evaluate()`` call ran against. The type narrowing here
    is intentional — resume is undefined against a moving dataset HEAD.
    """

    experiment: experiment_module.Experiment
    dataset: dataset.DatasetVersion
    completed_runs_by_item_id: Mapping[str, int]
    default_runs_per_item: int
    dataset_filter_string: Optional[str]
    nb_samples: Optional[int]
    candidate_dataset_item_ids: Optional[List[str]]


def prepare_resume_context(
    client: opik_client.Opik,
    experiment_id: str,
    *,
    checkpoint_reader: Optional[CheckpointReader] = None,
) -> ResumeContext:
    """
    Build a :class:`ResumeContext` from a stored experiment.

    ``checkpoint_reader`` defaults to the local-file reader but can be
    swapped (tests, alternate storage). Returning ``None`` from the reader
    when the experiment requires a checkpoint raises
    :class:`LocalCheckpointMissing`.

    Raises:
        opik.exceptions.ExperimentNotFound: when the experiment does not exist.
        ExperimentNotResumable: when the experiment is marked non-resumable.
        LocalCheckpointMissing: when a required checkpoint is unreachable.
    """
    reader = checkpoint_reader or checkpoint_module.read_checkpoint

    experiment = client.get_experiment_by_id(experiment_id)
    persisted = _require_resumable_state(
        experiment_id=experiment_id,
        persisted=state_module.read_resume_state(experiment),
    )

    candidate_ids: Optional[List[str]] = None
    if persisted.requires_local_checkpoint:
        candidate_ids = reader(experiment_id)
        if candidate_ids is None:
            raise opik_exceptions.LocalCheckpointMissing(
                f"Experiment {experiment_id} requires a checkpoint of "
                "resolved dataset item ids but it could not be read. Resume "
                "from the machine that wrote the checkpoint, or re-supply "
                "the original dataset_item_ids explicitly."
            )

    dataset_version = _resolve_dataset_version(
        client=client,
        dataset_name=experiment.dataset_name,
        project_name=experiment.project_name,
        dataset_version_name=persisted.dataset_version_name,
    )

    return ResumeContext(
        experiment=experiment,
        dataset=dataset_version,
        completed_runs_by_item_id=_count_completed_runs_by_item_id(experiment),
        default_runs_per_item=persisted.default_runs_per_item,
        dataset_filter_string=persisted.dataset_filter_string,
        nb_samples=persisted.nb_samples,
        candidate_dataset_item_ids=candidate_ids,
    )


def _require_resumable_state(
    *,
    experiment_id: str,
    persisted: Optional[state_module.PersistedResumeState],
) -> state_module.ResumableState:
    """
    Dispatch on the sum type returned by :func:`read_resume_state`.

    Returns the :class:`ResumableState` payload when present; raises
    :class:`ExperimentNotResumable` for all non-resumable paths (missing
    blob, explicit non-resumable marker).
    """
    if persisted is None:
        raise opik_exceptions.ExperimentNotResumable(
            f"Experiment {experiment_id} has no resume state in its config "
            "(created with an older SDK version or by an external client). "
            "Resume requires a pinned dataset version that was not recorded."
        )
    if isinstance(persisted, state_module.NonResumableState):
        raise opik_exceptions.ExperimentNotResumable(
            f"Experiment {experiment_id} cannot be resumed: {persisted.reason}"
        )
    return persisted


def _resolve_dataset_version(
    *,
    client: opik_client.Opik,
    dataset_name: str,
    project_name: Optional[str],
    dataset_version_name: str,
) -> dataset.DatasetVersion:
    """
    Always returns a :class:`DatasetVersion` pinned to the original run's
    version. The caller has already validated via :func:`_ensure_resumable`
    that ``dataset_version_name`` is non-empty.
    """
    dataset_ = client.get_dataset(name=dataset_name, project_name=project_name)
    return dataset_.get_version_view(dataset_version_name)


def _count_completed_runs_by_item_id(
    experiment: experiment_module.Experiment,
) -> Mapping[str, int]:
    """
    Count fully-completed trials per dataset item.

    A trial counts as fully completed only when the trace carries the
    happy-path marker flipped to ``False``. The marker is seeded to
    ``True`` when the trace is built (see
    ``engine.helpers.EVALUATION_PENDING_METADATA_KEY``) and flipped only
    after task + scoring + score-logging all returned. Any failure —
    sync exception, KeyboardInterrupt, process kill before the happy
    line — leaves the marker at its default and the trial is replayed.

    Trials produced by an SDK version older than the marker rollout will
    not carry the key at all; those are also treated as incomplete and
    replayed, which is the safe default.
    """
    counts: Dict[str, int] = {}
    for item in experiment.get_items():
        if not is_trial_fully_completed(item):
            continue
        counts[item.dataset_item_id] = counts.get(item.dataset_item_id, 0) + 1
    return counts


def is_trial_fully_completed(
    item: experiment_item.ExperimentItemContent,
) -> bool:
    metadata = item.trace_metadata or {}
    return metadata.get(engine_helpers.EVALUATION_PENDING_METADATA_KEY) is False
