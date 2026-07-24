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

# Bumped to 2 for OPIK-7162: the temp-destination ordering changed what the
# resume handles are. v1 checkpoints stored ``source_name_after_rename`` and
# assumed the destination already lived under the original name; v2 stores the
# source's *original* name (it isn't renamed until success) and the temp
# destination name (``<name>__migrating``). A v1 checkpoint therefore describes
# a different physical layout, so ``load_or_create`` treats it as incompatible
# and starts fresh rather than mis-resolving datasets.
SCHEMA_VERSION = 2


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


def checkpoint_dir() -> Path:
    """Per-user directory holding migration checkpoints: ``~/.opik/migrate-checkpoints``.

    Deliberately a FIXED, cwd-independent location (alongside the SDK's existing
    ``~/.opik.config``) rather than next to the audit log. The audit log defaults
    to the working directory, so anchoring the checkpoint there broke resume when
    the operator re-ran from a different folder -- the lookup missed and the run
    silently restarted. A fixed home-dir location makes resume work regardless of
    cwd for the common single-machine case.

    (A globally-robust location for CI with an ephemeral/differing HOME, and for
    containers recreated after an OOM where no in-container path survives without
    a mounted volume, is tracked as a follow-up: an ``OPIK_MIGRATE_CHECKPOINT_DIR``
    override + docs. This default doesn't foreclose that.)
    """
    return Path.home() / ".opik" / "migrate-checkpoints"


def checkpoint_path(key: str) -> Path:
    """On-disk path for a migration's checkpoint, keyed by the composite hash.

    The name is derived from the composite ``key`` (workspace+project+dataset),
    not a timestamp, so a re-run finds the prior run's checkpoint deterministically.
    """
    return checkpoint_dir() / f"opik-migrate-checkpoint-{key}.json"


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

    Persisted as JSON at ``checkpoint_path(key)``. Mutated in place
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
    # Set True once the dataset-level phases (create temp destination, replay
    # versions, cascade optimizations) have all completed on some run. Under the
    # OPIK-7162 ordering these write into a TEMP-named destination and the source
    # keeps its original name; the name handoff (rename source -> _v1, promote
    # temp -> original) happens only at the very end. Replay isn't idempotent
    # (it would duplicate versions), so a resumed run skips it and rebuilds the
    # remaps read-only from the temp destination (see ``reconstruct_remaps``),
    # then finishes the still-pending cascade + handoff.
    dataset_phase_done: bool = False
    # The source dataset's id and its ORIGINAL name. Under the temp-dest ordering
    # the source is not renamed until the run succeeds, so at resume time it
    # still carries its original name -- unlike the pre-OPIK-7162 model, which
    # stored the post-rename ``<name>_v1``. Resolved by id; the name is used for
    # source-side item reads during remap reconstruction.
    source_dataset_id: Optional[str] = None
    source_name: Optional[str] = None
    # The temp destination name (``<name>__migrating``) the first run wrote into.
    # A resumed run re-finds the destination by this name because the promote-to-
    # original step hasn't run yet (it's part of the pending handoff).
    temp_dest_name: Optional[str] = None
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

    def mark_dataset_phase_done(
        self, *, source_dataset_id: str, source_name: str, temp_dest_name: str
    ) -> None:
        """Record that create-temp/replay/optimizations have all completed,
        along with the source dataset's id + original name and the temp
        destination name, so a resumed run can re-resolve both datasets and
        rebuild the remaps before finishing the pending cascade + handoff.
        """
        self.dataset_phase_done = True
        self.source_dataset_id = source_dataset_id
        self.source_name = source_name
        self.temp_dest_name = temp_dest_name

    def to_dict(self) -> Dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "key": self.key,
            "workspace": self.workspace,
            "project": self.project,
            "dataset": self.dataset,
            "total_experiments": self.total_experiments,
            "dataset_phase_done": self.dataset_phase_done,
            "source_dataset_id": self.source_dataset_id,
            "source_name": self.source_name,
            "temp_dest_name": self.temp_dest_name,
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

        A write failure (read-only or full disk, permissions) is logged and
        swallowed rather than raised: the checkpoint is only a resume aid, so a
        failure to persist it must never abort an otherwise-healthy migration.
        The run continues; it just won't be resumable from this point.
        """
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
            tmp_path.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")
            os.replace(tmp_path, self.path)
        except OSError as exc:
            LOGGER.warning(
                "Could not write migrate checkpoint to %s (%s); the migration "
                "will continue but won't be resumable from here.",
                self.path,
                exc,
            )

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


def _require_opt_str(value: Any, field_name: str) -> Optional[str]:
    """Return ``value`` if it's a string or ``None``, else raise ``ValueError``.

    Guards the resume-handle fields (``source_dataset_id`` / ``source_name`` /
    ``temp_dest_name``) loaded from a checkpoint: they flow unvalidated into
    ``client.get_dataset(name=...)`` and ``stream_dataset_items(dataset_name=...)``
    on resume, so a hand-edited non-string (list/dict/int) would otherwise reach
    the API. Callers catch the ``ValueError`` and fall back to a fresh
    checkpoint, matching the corrupt-JSON recovery contract used for the id
    collections and ``dataset_phase_done``.
    """
    if value is not None and not isinstance(value, str):
        raise ValueError(f"{field_name} must be a string or null, got {value!r}")
    return value


def load_or_create(
    *,
    workspace: str,
    project: str,
    dataset: str,
) -> Optional[MigrationCheckpoint]:
    """Load an existing checkpoint for this composite key, or create a fresh one.

    The checkpoint lives at a fixed per-user path (see ``checkpoint_dir``),
    independent of the working directory, so a re-run from any folder finds it.

    Returns ``None`` when the checkpoint location can't even be resolved -- e.g.
    ``Path.home()`` raises ``RuntimeError`` on a homeless environment (some CI /
    container setups). A checkpoint is only a resume aid, so the caller treats
    ``None`` as "run without resume support" and the migration proceeds
    unimpeded rather than crashing before it starts.

    A checkpoint file that is missing, unreadable, corrupt (truncated JSON from
    an interrupted write on a filesystem without atomic replace), or written by
    a newer schema is treated as "no prior progress" -- the run starts clean
    rather than crashing. That's the safe default: a fresh start re-migrates
    everything (correct, just slower), whereas trusting a corrupt checkpoint
    could skip real work.
    """
    try:
        key = checkpoint_key(workspace, project, dataset)
        path = checkpoint_path(key)
    except (OSError, RuntimeError) as exc:
        LOGGER.warning(
            "Could not resolve a migrate checkpoint location (%s); the "
            "migration will run without resume support.",
            exc,
        )
        return None
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
        # ``dataset_phase_done`` gates the whole resume-vs-full-plan decision,
        # so a truthy non-bool (the string "false", a non-empty list) must NOT
        # be coerced to True and silently force a resume plan that skips
        # rename/create/replay. Require a real bool; anything else is malformed
        # -> fall back to fresh (same contract as the id-list validation).
        dataset_phase_done = data.get("dataset_phase_done", False)
        if not isinstance(dataset_phase_done, bool):
            raise ValueError(
                f"dataset_phase_done must be a bool, got {dataset_phase_done!r}"
            )
        return MigrationCheckpoint(
            key=key,
            workspace=workspace,
            project=project,
            dataset=dataset,
            path=path,
            total_experiments=int(data.get("total_experiments", 0)),
            dataset_phase_done=dataset_phase_done,
            source_dataset_id=_require_opt_str(
                data.get("source_dataset_id"), "source_dataset_id"
            ),
            source_name=_require_opt_str(data.get("source_name"), "source_name"),
            temp_dest_name=_require_opt_str(
                data.get("temp_dest_name"), "temp_dest_name"
            ),
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
