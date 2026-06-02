"""
Evaluator-facing glue that keeps :mod:`opik.evaluation.evaluator` thin.

Two responsibilities:

1. Build the resume state for each evaluation entrypoint and embed it into
   ``experiment_config``. The entrypoints differ in which kwargs flow into
   the state, so a small per-entrypoint helper lives here rather than in
   :mod:`state` (which stays generic).

2. After items are resolved, write a local checkpoint of their ids when
   iteration cannot be rebuilt from configuration alone (sampler or explicit
   ``dataset_item_ids`` were used at evaluation time).

The checkpoint writer is injectable so this module does not hard-depend on
local-file persistence: alternate stores can plug in for tests or future
backends.
"""

import logging
from typing import Any, Callable, Dict, List, Optional, Union

from ...api_objects.dataset import dataset, dataset_item
from ..samplers import base_dataset_sampler
from . import checkpoint as checkpoint_module
from . import state as state_module

LOGGER = logging.getLogger(__name__)


CheckpointWriter = Callable[[str, List[str]], None]


_NO_DATASET_VERSION_REASON = (
    "evaluation ran against a dataset with no versioning; resume requires a "
    "pinned dataset version so iteration is reproducible against the same "
    "set of items the original run saw"
)


def resume_state_for_evaluate(
    *,
    experiment_config: Optional[Dict[str, Any]],
    dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
    trial_count: int,
    dataset_filter_string: Optional[str],
    nb_samples: Optional[int],
    dataset_sampler: Optional[base_dataset_sampler.BaseDatasetSampler],
    dataset_item_ids: Optional[List[str]],
) -> Dict[str, Any]:
    """Build the resume blob for ``evaluate`` / ``evaluate_prompt`` / ``evaluate_optimization_trial``."""
    dataset_version_name = _dataset_version_name_or_none(dataset_)
    if dataset_version_name is None:
        return state_module.embed_non_resumable_state(
            experiment_config,
            state_module.NonResumableState(reason=_NO_DATASET_VERSION_REASON),
        )

    return state_module.embed_resumable_state(
        experiment_config,
        state_module.ResumableState(
            default_runs_per_item=trial_count,
            dataset_filter_string=dataset_filter_string,
            dataset_version_name=dataset_version_name,
            nb_samples=nb_samples,
            requires_local_checkpoint=(
                dataset_sampler is not None or dataset_item_ids is not None
            ),
        ),
    )


def write_checkpoint_if_needed(
    *,
    experiment_id: str,
    resolved_items: List[dataset_item.DatasetItem],
    dataset_item_ids: Optional[List[str]],
    dataset_sampler: Optional[base_dataset_sampler.BaseDatasetSampler],
    checkpoint_writer: Optional[CheckpointWriter] = None,
) -> None:
    """
    Snapshot the resolved item ids when iteration cannot be rebuilt from
    configuration alone (sampler or explicit ``dataset_item_ids`` was used).

    Called by the entrypoint after items have been materialized. No-op when
    iteration is fully config-driven — the resume state alone is enough to
    reproduce it on resume.

    ``checkpoint_writer`` defaults to ``checkpoint.write_checkpoint``
    resolved at call time, so module-level patches on the checkpoint module
    take effect in tests.
    """
    if dataset_sampler is None and dataset_item_ids is None:
        return

    writer = checkpoint_writer or checkpoint_module.write_checkpoint
    writer(experiment_id, [item.id for item in resolved_items])


def _dataset_version_name_or_none(
    dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
) -> Optional[str]:
    """
    Return the version name to pin for resume.

    For a ``DatasetVersion`` we have the version name directly. For a
    ``Dataset`` we use the latest version's name at the moment of evaluation
    — the same version id ``create_experiment`` writes to the experiment
    record. Returns None when the dataset has no versions.
    """
    version_info = dataset_.get_version_info()
    if version_info is None:
        return None
    return version_info.version_name
