"""Local, per-experiment checkpoint store for ``opik migrate dataset``.

When a migration is interrupted (crash, network drop, OOM as seen in
OPIK-7152), a re-run from the same machine resumes from the last completed
experiment instead of restarting. The checkpoint is deliberately **separate**
from the audit log:

* The audit log (``audit.py``) is an in-memory record written once, at the
  end of the run, to a per-run timestamped path. An **OOM SIGKILL** -- the
  exact failure this feature exists for -- cannot be caught by Python, so the
  end-of-run write never happens and the audit log isn't on disk to resume
  from. The checkpoint is flushed after **every** completed experiment so it
  survives an uncatchable kill.
* The checkpoint's path is **deterministic** (derived from the composite key
  ``workspace + project + dataset``) so a re-run can find the prior run's
  progress without scanning or guessing. The audit log keeps its per-run
  timestamped name untouched.

Granularity is **per-experiment** (ticket scope): an experiment is recorded
``completed`` only on successful ``recreate_experiment``. The completed set is
a **set of source experiment ids**, not a linear cursor -- experiment order
isn't guaranteed stable across runs, so resume skips *any* completed id rather
than stopping at a high-water mark.

The ``in_flight`` record captures the experiment currently being migrated plus
the destination ids written for it. On resume, that partial data is deleted
before the experiment is re-migrated -- the backend does **not** cascade-delete
it (``recreate_experiment`` mints fresh ids every run, so a naive re-run would
duplicate rather than overwrite), so the SDK cleans it up client-side.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

LOGGER = logging.getLogger(__name__)

SCHEMA_VERSION = 1


def checkpoint_key(workspace: str, project: str, dataset: str) -> str:
    """Composite key identifying one migration: ``workspace + project + dataset``.

    The key is content-addressed via a short SHA-256 digest so it maps to a
    filesystem-safe, fixed-length filename regardless of what characters the
    workspace / project / dataset names contain (spaces, slashes, unicode).
    A ``\\x00`` separator can't appear in any of the three names, so the digest
    is collision-free across distinct tuples.
    """
    raw = "\x00".join([workspace, project, dataset]).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:16]


def checkpoint_path(audit_path: Path, key: str) -> Path:
    """Location of the checkpoint file: adjacent to the audit log.

    Placing it in the audit log's directory means it travels with an explicit
    ``--audit-log`` path and, by default, lands in the working directory next
    to ``opik-migrate-<timestamp>.json``. The name is derived from the
    composite ``key`` (not the timestamp) so resume can find it deterministically.
    """
    return audit_path.parent / f"opik-migrate-checkpoint-{key}.json"


@dataclass
class InFlightExperiment:
    """The experiment currently being migrated and the destination ids
    written for it, so a resume can clean up its partial data first.

    ``dest_trace_ids`` accumulates as destination traces are minted; deleting
    them on resume cascades their spans on the backend. ``dest_experiment_id``
    is the destination experiment row created by ``recreate_experiment`` (its
    last step, so it's only set if the interruption landed after trace/span
    writes) -- resume deletes that exact row rather than matching by name, since
    experiment names are not unique in the destination dataset and a name match
    could delete an unrelated peer experiment. ``dest_dataset_id`` +
    ``experiment_name`` are retained for diagnostics/logging.
    """

    source_experiment_id: str
    experiment_name: Optional[str] = None
    dest_dataset_id: Optional[str] = None
    dest_experiment_id: Optional[str] = None
    dest_trace_ids: List[str] = field(default_factory=list)


@dataclass
class MigrationCheckpoint:
    """Resumable per-experiment progress for one migration.

    Persisted as JSON at ``checkpoint_path(audit_path, key)``. Mutated in place
    by the cascade and flushed after each experiment. ``path`` is the on-disk
    location; it is not serialized.
    """

    key: str
    workspace: str
    project: str
    dataset: str
    path: Path
    total_experiments: int = 0
    completed_experiment_ids: Set[str] = field(default_factory=set)
    in_flight: Optional[InFlightExperiment] = None
    schema_version: int = SCHEMA_VERSION

    @property
    def completed_count(self) -> int:
        return len(self.completed_experiment_ids)

    def is_completed(self, source_experiment_id: str) -> bool:
        return source_experiment_id in self.completed_experiment_ids

    def mark_in_flight(
        self,
        source_experiment_id: str,
        *,
        experiment_name: Optional[str],
        dest_dataset_id: Optional[str],
    ) -> None:
        self.in_flight = InFlightExperiment(
            source_experiment_id=source_experiment_id,
            experiment_name=experiment_name,
            dest_dataset_id=dest_dataset_id,
        )

    def record_dest_trace_ids(self, trace_ids: List[str]) -> None:
        """Append destination trace ids to the in-flight record.

        No-op when nothing is in flight -- the cascade always calls
        ``mark_in_flight`` before writing traces, so this only guards against
        an unexpected call ordering rather than a real code path.
        """
        if self.in_flight is None:
            return
        self.in_flight.dest_trace_ids.extend(trace_ids)

    def record_dest_experiment_id(self, dest_experiment_id: str) -> None:
        """Record the destination experiment id on the in-flight record so a
        resume can delete that exact row instead of matching by (non-unique)
        name. No-op when nothing is in flight.
        """
        if self.in_flight is None:
            return
        self.in_flight.dest_experiment_id = dest_experiment_id

    def mark_completed(self, source_experiment_id: str) -> None:
        self.completed_experiment_ids.add(source_experiment_id)
        self.in_flight = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "key": self.key,
            "workspace": self.workspace,
            "project": self.project,
            "dataset": self.dataset,
            "total_experiments": self.total_experiments,
            # Sorted for a stable, diff-friendly on-disk representation.
            "completed_experiment_ids": sorted(self.completed_experiment_ids),
            "in_flight": asdict(self.in_flight) if self.in_flight else None,
        }

    def flush(self) -> None:
        """Atomically write the checkpoint to disk.

        Writes to a sibling ``.tmp`` file then ``os.replace`` (atomic on the
        same filesystem) so an interruption mid-write can't leave a truncated,
        unparseable checkpoint -- the old file stays intact until the new one
        is fully written.
        """
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp_path.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")
        os.replace(tmp_path, self.path)

    def delete(self) -> None:
        """Remove the checkpoint file after a fully successful migration.

        Idempotent: a missing file is fine (nothing to clean up).
        """
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass


def _require_str_list(value: Any, field_name: str) -> List[str]:
    """Return ``value`` as a list of strings, or raise ``ValueError``.

    Guards the id collections loaded from a checkpoint: ``set()``/``list()``
    happily consume a bare string by splitting it into characters, so a
    corrupt ``"abc"`` would become ``{"a", "b", "c"}`` and silently corrupt
    resume. Callers catch the ``ValueError`` and fall back to a fresh
    checkpoint, matching the corrupt-JSON recovery path.
    """
    if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
        raise ValueError(f"{field_name} must be a list of strings, got {value!r}")
    return value


def load_or_create(
    *,
    audit_path: Path,
    workspace: str,
    project: str,
    dataset: str,
) -> MigrationCheckpoint:
    """Load an existing checkpoint for this composite key, or create a fresh one.

    A checkpoint file that is missing, unreadable, corrupt (truncated JSON from
    an interrupted write on a filesystem without atomic replace), or written by
    a newer schema is treated as "no prior progress" -- the run starts clean
    rather than crashing. That's the safe default: a fresh start re-migrates
    everything (correct, just slower), whereas trusting a corrupt checkpoint
    could skip real work.
    """
    key = checkpoint_key(workspace, project, dataset)
    path = checkpoint_path(audit_path, key)
    fresh = MigrationCheckpoint(
        key=key,
        workspace=workspace,
        project=project,
        dataset=dataset,
        path=path,
    )
    if not path.exists():
        return fresh
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        LOGGER.warning(
            "Ignoring unreadable migrate checkpoint at %s (%s); starting fresh.",
            path,
            exc,
        )
        return fresh
    if data.get("schema_version") != SCHEMA_VERSION:
        LOGGER.warning(
            "Ignoring migrate checkpoint at %s written by schema_version=%s "
            "(this tool speaks %s); starting fresh.",
            path,
            data.get("schema_version"),
            SCHEMA_VERSION,
        )
        return fresh

    # Reconstruct the structured fields defensively: a hand-edited or
    # partially-written checkpoint can carry the right schema_version but a
    # wrong-typed ``in_flight`` (e.g. a string, or a dict missing
    # ``source_experiment_id``) or a wrong-typed id list. Any such shape
    # mismatch falls back to ``fresh`` -- same recovery contract as the
    # unreadable/corrupt-JSON and foreign-schema paths above -- rather than
    # silently corrupting resume or crashing the CLI.
    try:
        # ``set(...)`` / ``list(...)`` accept a bare string and split it into
        # characters WITHOUT raising, which would silently seed the completed
        # set (or trace-id list) with wrong values -- making the cascade skip
        # or re-run the wrong experiments. Validate the id collections are
        # lists of strings up front and raise (caught below -> fresh) otherwise.
        completed_ids = _require_str_list(
            data.get("completed_experiment_ids", []), "completed_experiment_ids"
        )
        in_flight_data = data.get("in_flight")
        in_flight = (
            InFlightExperiment(
                source_experiment_id=in_flight_data["source_experiment_id"],
                experiment_name=in_flight_data.get("experiment_name"),
                dest_dataset_id=in_flight_data.get("dest_dataset_id"),
                dest_experiment_id=in_flight_data.get("dest_experiment_id"),
                dest_trace_ids=_require_str_list(
                    in_flight_data.get("dest_trace_ids", []), "dest_trace_ids"
                ),
            )
            if in_flight_data
            else None
        )
        return MigrationCheckpoint(
            key=key,
            workspace=workspace,
            project=project,
            dataset=dataset,
            path=path,
            total_experiments=int(data.get("total_experiments", 0)),
            completed_experiment_ids=set(completed_ids),
            in_flight=in_flight,
        )
    except (TypeError, KeyError, ValueError) as exc:
        LOGGER.warning(
            "Ignoring malformed migrate checkpoint at %s (%s); starting fresh.",
            path,
            exc,
        )
        return fresh
