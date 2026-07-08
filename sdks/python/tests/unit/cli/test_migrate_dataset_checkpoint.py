"""Tests for ``opik migrate dataset`` checkpoint/resume (OPIK-7168).

Two layers:

* ``TestMigrationCheckpoint`` -- the checkpoint store in isolation
  (``opik.cli.migrate.checkpoint``): key derivation, atomic round-trip,
  in-flight tracking, corrupt/foreign-schema tolerance, delete lifecycle.

* ``TestCascadeResume`` -- the cascade's resume behaviour
  (``cascade_experiments`` with a ``checkpoint``): skip already-completed
  experiments, re-migrate an interrupted experiment after deleting its partial
  destination data, and seed the progress callback so a resumed run reports the
  right completed count instead of starting at 0.

The cascade tests reuse the elaborate REST/client fakes from
``test_migrate_dataset_experiments_cascade`` so the resume behaviour is
exercised against the same call surface the real cascade drives.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, List, Tuple
from unittest.mock import MagicMock

import pytest

from opik.cli.migrate import checkpoint as checkpoint_module
from opik.cli.migrate.checkpoint import (
    SCHEMA_VERSION,
    MigrationCheckpoint,
    checkpoint_key,
    checkpoint_path,
    load_or_create,
)
from opik.cli.migrate.datasets.experiments import cascade_experiments

from .test_migrate_dataset_experiments_cascade import (
    _Experiment,
    _ExperimentItem,
    _Trace,
    _audit,
    _cascade_rest_client,
    _client_with_recreate_capture,
)


# ---------------------------------------------------------------------------
# Checkpoint store (unit)
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _isolate_checkpoint_dir(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """Point the checkpoint store at a tmp dir instead of the real ~/.opik.

    The checkpoint now lives at a fixed per-user path (``checkpoint_dir``),
    independent of cwd/--audit-log. Redirecting it here keeps the unit tests
    hermetic and off the developer's home directory.
    """
    cp_dir = tmp_path / "migrate-checkpoints"
    cp_dir.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(checkpoint_module, "checkpoint_dir", lambda: cp_dir)


class TestMigrationCheckpoint:
    def test_checkpoint_key__stable_and_distinct_per_tuple(self) -> None:
        key = checkpoint_key("ws", "proj", "ds")
        # Deterministic: same tuple -> same key across calls.
        assert key == checkpoint_key("ws", "proj", "ds")
        # Distinct tuples -> distinct keys, including the ambiguous shift of a
        # separator across the three components (the \x00 join prevents
        # "a"+"bc" colliding with "ab"+"c").
        assert checkpoint_key("a", "bc", "d") != checkpoint_key("ab", "c", "d")

    def test_checkpoint_path__lives_in_checkpoint_dir_keyed_by_hash(self) -> None:
        key = checkpoint_key("ws", "proj", "ds")
        path = checkpoint_path(key)
        assert path.parent == checkpoint_module.checkpoint_dir()
        assert path.name == f"opik-migrate-checkpoint-{key}.json"

    def test_load_or_create__no_file__starts_fresh(self, tmp_path: Path) -> None:
        cp = load_or_create(
            workspace="ws",
            project="proj",
            dataset="ds",
        )
        assert cp.completed_count == 0
        assert cp.in_flight is None
        assert not cp.path.exists()

    def test_flush_then_load__round_trips_completed_and_in_flight(
        self, tmp_path: Path
    ) -> None:
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        cp.total_experiments = 3
        cp.mark_in_flight(
            "src-exp-2", experiment_name="exp-2", dest_dataset_id="dest-ds"
        )
        cp.record_dest_trace_ids(["dest-trace-a", "dest-trace-b"])
        cp.mark_completed("src-exp-1")
        cp.flush()

        reloaded = load_or_create(workspace="ws", project="proj", dataset="ds")
        assert reloaded.total_experiments == 3
        assert reloaded.completed_experiment_ids == {"src-exp-1"}
        # ``mark_completed`` clears in_flight, so only the completed set survives.
        assert reloaded.in_flight is None

    def test_flush_preserves_in_flight_when_not_completed(self, tmp_path: Path) -> None:
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        cp.mark_in_flight(
            "src-exp-2", experiment_name="exp-2", dest_dataset_id="dest-ds"
        )
        cp.record_dest_trace_ids(["dest-trace-a"])
        cp.flush()

        reloaded = load_or_create(workspace="ws", project="proj", dataset="ds")
        assert reloaded.in_flight is not None
        assert reloaded.in_flight.source_experiment_id == "src-exp-2"
        assert reloaded.in_flight.experiment_name == "exp-2"
        assert reloaded.in_flight.dest_dataset_id == "dest-ds"
        assert reloaded.in_flight.dest_trace_ids == ["dest-trace-a"]

    def test_load_or_create__corrupt_file__starts_fresh(self, tmp_path: Path) -> None:
        # A truncated / unparseable checkpoint (e.g. an interrupted write on a
        # filesystem without atomic replace) must be treated as "no progress",
        # not crash the migration.
        key = checkpoint_key("ws", "proj", "ds")
        checkpoint_path(key).write_text('{"schema_version": 1, "comp')

        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        assert cp.completed_count == 0
        assert cp.in_flight is None

    def test_load_or_create__non_bool_dataset_phase_done__starts_fresh(
        self, tmp_path: Path
    ) -> None:
        # ``dataset_phase_done`` gates resume-vs-full-plan, so a truthy non-bool
        # (the string "false", a non-empty list) must NOT be coerced to True and
        # force a resume that skips rename/create/replay. A non-bool is
        # malformed -> fresh.
        key = checkpoint_key("ws", "proj", "ds")
        for bad in ('"false"', "[1]", "1"):
            # Use the CURRENT schema so the load reaches the non-bool guard
            # rather than short-circuiting on a schema-version mismatch.
            checkpoint_path(key).write_text(
                f'{{"schema_version": {SCHEMA_VERSION}, "dataset_phase_done": {bad}}}'
            )
            cp = load_or_create(workspace="ws", project="proj", dataset="ds")
            assert cp.dataset_phase_done is False

    def test_load_or_create__malformed_in_flight__starts_fresh(
        self, tmp_path: Path
    ) -> None:
        # A checkpoint with the right schema_version but a wrong-shaped
        # ``in_flight`` (here a bare string, or a dict missing the required
        # ``source_experiment_id``) must fall back to fresh rather than crash
        # the CLI with a TypeError/KeyError.
        key = checkpoint_key("ws", "proj", "ds")
        for bad_in_flight in ('"not-a-dict"', '{"experiment_name": "x"}'):
            checkpoint_path(key).write_text(
                '{"schema_version": 1, "completed_experiment_ids": ["e1"], '
                f'"in_flight": {bad_in_flight}}}'
            )
            cp = load_or_create(workspace="ws", project="proj", dataset="ds")
            assert cp.completed_count == 0
            assert cp.in_flight is None

    def test_load_or_create__corrupt_completed_ids__starts_fresh(
        self, tmp_path: Path
    ) -> None:
        # ``set("abc")`` / ``set([1, 2])`` don't raise, so a corrupt
        # ``completed_experiment_ids`` (a bare string, or a list with non-string
        # entries) would silently seed the wrong completed set and make the
        # cascade skip/re-run the wrong experiments. It must fall back to fresh.
        key = checkpoint_key("ws", "proj", "ds")
        for bad in ('"abc"', "[1, 2, 3]", "{}"):
            checkpoint_path(key).write_text(
                f'{{"schema_version": 1, "completed_experiment_ids": {bad}}}'
            )
            cp = load_or_create(workspace="ws", project="proj", dataset="ds")
            assert cp.completed_experiment_ids == set()

    def test_load_or_create__corrupt_dest_trace_ids__starts_fresh(
        self, tmp_path: Path
    ) -> None:
        # Same silent-corruption guard for the in-flight trace-id list.
        key = checkpoint_key("ws", "proj", "ds")
        checkpoint_path(key).write_text(
            '{"schema_version": 1, "in_flight": '
            '{"source_experiment_id": "e2", "dest_trace_ids": "trace-1"}}'
        )
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        assert cp.in_flight is None
        assert cp.completed_count == 0

    def test_load_or_create__non_string_resume_names__starts_fresh(
        self, tmp_path: Path
    ) -> None:
        # OPIK-7162: source_dataset_id / source_name / temp_dest_name are the
        # resume handles that flow unvalidated into client.get_dataset(name=...)
        # and stream_dataset_items(dataset_name=...). A hand-edited non-string
        # would otherwise reach the API; it must fall back to fresh, matching
        # the same corrupt-recovery contract as the id collections. Uses the
        # CURRENT schema so the load reaches the field guard (not the schema
        # short-circuit).
        key = checkpoint_key("ws", "proj", "ds")
        for field in ("source_dataset_id", "source_name", "temp_dest_name"):
            for bad in ("[1]", "{}", "5"):
                checkpoint_path(key).write_text(
                    f'{{"schema_version": {SCHEMA_VERSION}, '
                    f'"dataset_phase_done": true, "{field}": {bad}}}'
                )
                cp = load_or_create(workspace="ws", project="proj", dataset="ds")
                # Fell back to fresh: no phase-done carried over from the bad file.
                assert cp.dataset_phase_done is False
                assert getattr(cp, field) is None

    def test_load_or_create__unresolvable_home__returns_none(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # A homeless environment (Path.home() raising, as in some CI/containers)
        # must NOT crash the migration before it starts -- load_or_create
        # returns None and the caller runs without resume support.
        def _boom() -> Path:
            raise RuntimeError("Could not determine home directory")

        monkeypatch.setattr(checkpoint_module, "checkpoint_dir", _boom, raising=True)
        assert load_or_create(workspace="ws", project="proj", dataset="ds") is None

    def test_flush__write_failure__swallowed_not_raised(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # A checkpoint is only a resume aid: a read-only / full disk on flush
        # must be logged and swallowed, never abort an otherwise-healthy
        # migration.
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        assert cp is not None

        def _readonly(*_a: Any, **_k: Any) -> None:
            raise OSError("Read-only file system")

        monkeypatch.setattr(Path, "mkdir", _readonly, raising=True)
        cp.flush()  # must not raise

    def test_flush_then_load__round_trips_dest_experiment_id(
        self, tmp_path: Path
    ) -> None:
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        cp.mark_in_flight(
            "src-exp-2", experiment_name="exp-2", dest_dataset_id="dest-ds"
        )
        cp.record_dest_experiment_id("dest-exp-99")
        cp.flush()

        reloaded = load_or_create(workspace="ws", project="proj", dataset="ds")
        assert reloaded.in_flight is not None
        assert reloaded.in_flight.dest_experiment_id == "dest-exp-99"

    def test_load_or_create__foreign_schema_version__starts_fresh(
        self, tmp_path: Path
    ) -> None:
        key = checkpoint_key("ws", "proj", "ds")
        checkpoint_path(key).write_text(
            '{"schema_version": 999, "completed_experiment_ids": ["src-exp-1"]}'
        )

        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        # A newer schema we can't interpret is ignored -> fresh start.
        assert cp.completed_count == 0

    def test_delete__removes_file_and_is_idempotent(self, tmp_path: Path) -> None:
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        cp.flush()
        assert cp.path.exists()
        cp.delete()
        assert not cp.path.exists()
        # Idempotent: deleting again is a no-op, not an error.
        cp.delete()

    def test_flush__atomic__no_stray_tmp_file_left(self, tmp_path: Path) -> None:
        cp = load_or_create(workspace="ws", project="proj", dataset="ds")
        cp.flush()
        # The temp file used for the atomic write must have been renamed away.
        tmp_files = list(tmp_path.glob("*.tmp"))
        assert tmp_files == []


# ---------------------------------------------------------------------------
# Cascade resume behaviour
# ---------------------------------------------------------------------------


def _two_experiment_rig() -> Tuple[Any, Any]:
    """Two source experiments, each with one item/trace, wired end-to-end.

    Returns ``(rest_client, client)`` ready to pass to ``cascade_experiments``.
    """
    exp1 = _Experiment(id="src-exp-1", name="exp-1", dataset_version_id="src-v-1")
    exp2 = _Experiment(id="src-exp-2", name="exp-2", dataset_version_id="src-v-1")
    item1 = _ExperimentItem(
        id="i1",
        experiment_id="src-exp-1",
        trace_id="src-trace-1",
        dataset_item_id="src-ds-item-1",
    )
    item2 = _ExperimentItem(
        id="i2",
        experiment_id="src-exp-2",
        trace_id="src-trace-2",
        dataset_item_id="src-ds-item-1",
    )
    rest_client = _cascade_rest_client(
        experiments_by_dataset={"src-dataset-1": [exp1, exp2]},
        items_by_experiment={"exp-1": [item1], "exp-2": [item2]},
        traces_by_id={
            "src-trace-1": _Trace(id="src-trace-1"),
            "src-trace-2": _Trace(id="src-trace-2"),
        },
        spans_by_trace={"src-trace-1": [], "src-trace-2": []},
    )
    client = _client_with_recreate_capture(rest_client)
    return rest_client, client


def _run_cascade(
    rest_client: Any, client: Any, checkpoint: MigrationCheckpoint, **overrides: Any
) -> Tuple[Any, List[Tuple[int, int, str]]]:
    """Run ``cascade_experiments`` with a progress-capturing callback."""
    progress_calls: List[Tuple[int, int, str]] = []

    kwargs: dict = dict(
        source_dataset_id="src-dataset-1",
        target_dataset_name="MyDataset",
        target_project_name="DestProject",
        target_dataset_id="dest-dataset-1",
        version_remap={"src-v-1": "dest-v-1"},
        item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
        audit=_audit(),
        checkpoint=checkpoint,
        progress_callback=lambda c, t, label: progress_calls.append((c, t, label)),
    )
    kwargs.update(overrides)
    result = cascade_experiments(client, rest_client, **kwargs)
    return result, progress_calls


class TestCascadeResume:
    def test_skip_completed__does_not_recreate_already_done_experiment(
        self, tmp_path: Path
    ) -> None:
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )
        # Pretend exp-1 already migrated on a prior run.
        cp.mark_completed("src-exp-1")

        result, _ = _run_cascade(rest_client, client, cp)

        # Only the not-yet-done experiment is recreated.
        assert result.experiments_migrated == 1
        assert client.create_experiment.call_count == 1
        created_names = [
            call.kwargs["name"] for call in client.create_experiment.call_args_list
        ]
        assert created_names == ["exp-2"]

    def test_skip_completed__all_done__no_recreation(self, tmp_path: Path) -> None:
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )
        cp.mark_completed("src-exp-1")
        cp.mark_completed("src-exp-2")

        result, _ = _run_cascade(rest_client, client, cp)

        assert result.experiments_migrated == 0
        client.create_experiment.assert_not_called()

    def test_happyflow__marks_each_experiment_completed_and_flushes(
        self, tmp_path: Path
    ) -> None:
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )

        result, _ = _run_cascade(rest_client, client, cp)

        assert result.experiments_migrated == 2
        assert cp.completed_experiment_ids == {"src-exp-1", "src-exp-2"}
        # in_flight is cleared once the last experiment completes.
        assert cp.in_flight is None
        # Progress was flushed to disk (a re-run would see both done).
        reloaded = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )
        assert reloaded.completed_experiment_ids == {"src-exp-1", "src-exp-2"}

    def test_re_migrate_incomplete__deletes_partial_dest_data_before_rerun(
        self, tmp_path: Path
    ) -> None:
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )
        # exp-1 finished on the prior run; exp-2 was interrupted mid-flight with
        # two destination traces and its destination experiment row already
        # created (id recorded before creation).
        cp.mark_completed("src-exp-1")
        cp.mark_in_flight(
            "src-exp-2", experiment_name="exp-2", dest_dataset_id="dest-dataset-1"
        )
        cp.record_dest_trace_ids(["stale-dest-trace-1", "stale-dest-trace-2"])
        cp.record_dest_experiment_id("stale-dest-exp")

        _run_cascade(rest_client, client, cp)

        # Partial traces were deleted (spans cascade on the BE), so they don't
        # duplicate on the re-run.
        rest_client.traces.delete_traces.assert_called_once()
        deleted_trace_ids = rest_client.traces.delete_traces.call_args.kwargs["ids"]
        assert set(deleted_trace_ids) == {"stale-dest-trace-1", "stale-dest-trace-2"}
        # The exact recorded destination experiment row was deleted BY ID --
        # cleanup never does a name lookup, so no same-named peer can be hit.
        rest_client.experiments.delete_experiments_by_id.assert_called_once()
        deleted_exp_ids = (
            rest_client.experiments.delete_experiments_by_id.call_args.kwargs["ids"]
        )
        assert deleted_exp_ids == ["stale-dest-exp"]
        # in_flight is cleared after cleanup so a further crash won't re-delete.
        assert cp.in_flight is None

    def test_re_migrate_incomplete__no_experiment_row_yet__only_traces_deleted(
        self, tmp_path: Path
    ) -> None:
        # Interruption landed after traces were flushed but before the
        # experiment row was created: dest_experiment_id is None, so cleanup
        # deletes the traces and skips the experiment delete entirely (no
        # name lookup that could hit a peer).
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )
        cp.mark_in_flight(
            "src-exp-1", experiment_name="exp-1", dest_dataset_id="dest-dataset-1"
        )
        cp.record_dest_trace_ids(["stale-dest-trace-1"])
        # no record_dest_experiment_id -> dest_experiment_id stays None

        _run_cascade(rest_client, client, cp)

        rest_client.traces.delete_traces.assert_called_once()
        rest_client.experiments.delete_experiments_by_id.assert_not_called()

    def test_re_migrate_incomplete__records_dest_ids_before_backend_write(
        self, tmp_path: Path
    ) -> None:
        # OPIK-7168 (#533/#536): the destination trace ids and experiment id
        # must be flushed to the checkpoint BEFORE the backend write that
        # persists them, so a crash in that window still leaves them recorded
        # for the next run's cleanup. We assert the checkpoint file on disk
        # already carries this experiment's dest trace ids by the time the
        # trace-flush happens.
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )

        flushed_state: dict = {}
        original_flush = client.flush

        def _capture_on_first_flush() -> None:
            # On the first client.flush() (the trace flush), read back the
            # checkpoint from disk and snapshot its in-flight trace ids.
            if "dest_trace_ids" not in flushed_state:
                reloaded = load_or_create(
                    workspace="ws",
                    project="DestProject",
                    dataset="MyDataset",
                )
                flushed_state["dest_trace_ids"] = (
                    list(reloaded.in_flight.dest_trace_ids)
                    if reloaded.in_flight
                    else []
                )
            return original_flush()

        client.flush = MagicMock(side_effect=_capture_on_first_flush)

        _run_cascade(rest_client, client, cp)

        # By the time the backend trace flush ran, the checkpoint on disk had
        # already recorded this experiment's destination trace id.
        assert len(flushed_state["dest_trace_ids"]) == 1

    def test_progress_seeded__resumed_run_starts_at_completed_count(
        self, tmp_path: Path
    ) -> None:
        rest_client, client = _two_experiment_rig()
        cp = load_or_create(
            workspace="ws",
            project="DestProject",
            dataset="MyDataset",
        )
        cp.mark_completed("src-exp-1")

        _, progress_calls = _run_cascade(rest_client, client, cp)

        # First progress tick reports 1 already-completed of 2 total (not 0),
        # so the bar opens at 50% rather than restarting.
        assert progress_calls[0] == (1, 2, "exp-2")
        # Final tick snaps to total/total "done".
        assert progress_calls[-1] == (2, 2, "done")

    def test_no_checkpoint__cascade_unchanged(self) -> None:
        # Without a checkpoint the cascade behaves exactly as before: both
        # experiments migrate, no delete calls, progress counts from 0.
        rest_client, client = _two_experiment_rig()
        progress_calls: List[Tuple[int, int, str]] = []

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
            progress_callback=lambda c, t, label: progress_calls.append((c, t, label)),
        )

        assert result.experiments_migrated == 2
        rest_client.traces.delete_traces.assert_not_called()
        assert progress_calls[0] == (0, 2, "exp-1")
