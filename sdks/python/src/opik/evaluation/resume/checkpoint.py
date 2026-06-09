"""
Local checkpoint file holding the resolved dataset item ids for an experiment.

Used when iteration cannot be rebuilt from configuration alone — i.e., when
the original ``evaluate()`` call used a custom ``dataset_sampler`` or an
explicit ``dataset_item_ids`` list. We snapshot the resolved ids to disk
keyed by experiment id; resume re-reads them from the same machine.

The companion experiment metadata flag ``requires_local_checkpoint`` tells
the resume flow when to look for this file.
"""

import json
import logging
import os
from pathlib import Path
from typing import List, Optional

LOGGER = logging.getLogger(__name__)

LOCAL_CHECKPOINT_DIR = Path.home() / ".opik" / "resume"
CHECKPOINT_SCHEMA_VERSION = 1


def checkpoint_path(experiment_id: str) -> Path:
    """Return the on-disk path for an experiment's checkpoint file."""
    return LOCAL_CHECKPOINT_DIR / f"{experiment_id}.json"


def write_checkpoint(
    experiment_id: str,
    resolved_dataset_item_ids: List[str],
) -> Path:
    """
    Persist resolved item ids to ``~/.opik/resume/<experiment_id>.json``.

    Writes through a temp file + rename for atomicity, so a crashed write
    cannot leave a half-written file that resume would later misread.
    """
    LOCAL_CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)
    target = checkpoint_path(experiment_id)
    payload = {
        "schema_version": CHECKPOINT_SCHEMA_VERSION,
        "experiment_id": experiment_id,
        "resolved_dataset_item_ids": list(resolved_dataset_item_ids),
    }

    temp_path = target.with_suffix(target.suffix + ".tmp")
    temp_path.write_text(json.dumps(payload))
    os.replace(temp_path, target)
    return target


def read_checkpoint(experiment_id: str) -> Optional[List[str]]:
    """
    Return the resolved item ids previously written for this experiment, or
    None when the file does not exist, is unreadable, or is malformed.

    Callers decide whether a missing checkpoint is fatal (typically yes when
    the experiment metadata sets ``requires_local_checkpoint``).
    """
    target = checkpoint_path(experiment_id)
    if not target.exists():
        return None

    try:
        payload = json.loads(target.read_text())
    except (OSError, json.JSONDecodeError):
        LOGGER.warning(
            "Local resume checkpoint for experiment %s exists but could not "
            "be read; treating as missing.",
            experiment_id,
            exc_info=True,
        )
        return None

    if not isinstance(payload, dict):
        return None
    ids = payload.get("resolved_dataset_item_ids")
    if not isinstance(ids, list) or not all(isinstance(item, str) for item in ids):
        return None
    return ids


def delete_checkpoint(experiment_id: str) -> None:
    """
    Best-effort removal of the checkpoint file. Called after an experiment is
    fully complete so finished runs do not accumulate on disk.
    """
    target = checkpoint_path(experiment_id)
    try:
        target.unlink(missing_ok=True)
    except OSError:
        LOGGER.debug(
            "Failed to delete local resume checkpoint for experiment %s",
            experiment_id,
            exc_info=True,
        )
