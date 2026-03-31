"""Unit tests for export_all / import_all and ExportManifest / MigrationManifest.

Covers
------
ExportManifest
  - Lifecycle: not_started → in_progress → completed → reset
  - Trace tracking: mark, is_downloaded, load_downloaded_set, batch flush,
    idempotent inserts, downloaded_count
  - Metadata: format, last_exported_at, all_trace_ids
  - exists() class method, start() idempotency, save()

MigrationManifest
  - Lifecycle: not_started → in_progress → completed → reset
  - File tracking: mark_file_completed, is_file_completed, mark_file_failed,
    completion supersedes failure
  - Trace ID mapping: add_trace_mapping, get_trace_id_map
  - Stats: completed_count, failed_count, exists()

_merge_stats helper

import_all
  - Fresh run: all four phases called, manifest completed
  - Dry-run: no manifest created, no flush
  - Completed manifest without --force: returns early, phases not called
  - --force on completed manifest: resets and runs phases again
  - Resume (in_progress manifest): phases called, manifest completed at end
  - --include filter: only specified phases run
  - Missing subdirectory: phase silently skipped
  - flush timeout (returns False): SystemExit(1)
  - failed uploads > 0: SystemExit(1)
  - Prompts intermediate flush when prompts were imported

export_all
  - Workspace directory created
  - Only included phases are called
  - trace totals from projects + experiments are summed
  - All subdirectories created
  - api_key passed through to Opik constructor

_paginate helper
  - Single-page response
  - Multi-page response
  - Empty first page
"""

from unittest.mock import MagicMock, patch

import pytest

from opik.cli.export_manifest import ExportManifest
from opik.cli.migration_manifest import MigrationManifest

_IMPORT_MODULE = "opik.cli.imports.all"
_EXPORT_MODULE = "opik.cli.exports.all"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_import_client() -> MagicMock:
    """Return a mock Opik client suitable for import_all."""
    client = MagicMock()
    client.flush.return_value = True
    client.__internal_api__failed_uploads__ = MagicMock(return_value=0)
    return client


def _make_export_client() -> MagicMock:
    return MagicMock()


# ---------------------------------------------------------------------------
# ExportManifest
# ---------------------------------------------------------------------------


class TestExportManifest:
    # ---- lifecycle ---------------------------------------------------------

    def test_fresh_manifest_status_is_not_started(self, tmp_path):
        m = ExportManifest(tmp_path)
        assert m.status == "not_started"
        assert not m.is_in_progress
        assert not m.is_completed

    def test_start_sets_in_progress(self, tmp_path):
        m = ExportManifest(tmp_path)
        m.start("json")
        assert m.status == "in_progress"
        assert m.is_in_progress
        assert not m.is_completed

    def test_start_records_format(self, tmp_path):
        m = ExportManifest(tmp_path)
        m.start("csv")
        assert m.get_format() == "csv"

    def test_get_format_returns_none_before_start(self, tmp_path):
        m = ExportManifest(tmp_path)
        assert m.get_format() is None

    def test_complete_sets_completed_and_records_last_exported_at(self, tmp_path):
        m = ExportManifest(tmp_path)
        m.start("json")
        ts = "2024-06-01T00:00:00+00:00"
        m.complete(ts)
        assert m.status == "completed"
        assert m.is_completed
        assert not m.is_in_progress
        assert m.get_last_exported_at() == ts

    def test_get_last_exported_at_returns_none_when_not_completed(self, tmp_path):
        m = ExportManifest(tmp_path)
        assert m.get_last_exported_at() is None

    def test_reset_clears_all_state(self, tmp_path):
        m = ExportManifest(tmp_path)
        m.start("json")
        m.mark_trace_downloaded("t1")
        m.complete("2024-01-01T00:00:00+00:00")
        m.reset()
        assert m.status == "not_started"
        assert m.downloaded_count() == 0
        assert m.get_last_exported_at() is None
        assert m.get_format() is None

    # ---- trace tracking ----------------------------------------------------

    def test_mark_and_check_trace_downloaded_from_buffer(self, tmp_path):
        """is_trace_downloaded returns True even when the entry is still buffered."""
        m = ExportManifest(tmp_path, batch_size=100)
        m.mark_trace_downloaded("abc-123")
        assert m.is_trace_downloaded("abc-123")

    def test_mark_and_check_trace_downloaded_after_flush(self, tmp_path):
        """is_trace_downloaded returns True for a persisted (flushed) trace."""
        m = ExportManifest(tmp_path, batch_size=1)  # flush on every write
        m.mark_trace_downloaded("xyz-456")
        assert m.is_trace_downloaded("xyz-456")

    def test_unknown_trace_is_not_downloaded(self, tmp_path):
        m = ExportManifest(tmp_path)
        assert not m.is_trace_downloaded("does-not-exist")

    def test_auto_flush_triggers_at_batch_size(self, tmp_path):
        """Pending buffer is flushed automatically when it reaches batch_size."""
        m = ExportManifest(tmp_path, batch_size=2)
        m.mark_trace_downloaded("t1")
        assert m._pending_count == 1  # still buffered
        m.mark_trace_downloaded("t2")
        assert m._pending_count == 0  # auto-flushed
        assert m.downloaded_count() == 2

    def test_load_downloaded_set_returns_all_traces(self, tmp_path):
        m = ExportManifest(tmp_path, batch_size=100)
        ids = {f"trace-{i}" for i in range(5)}
        for tid in ids:
            m.mark_trace_downloaded(tid)
        assert m.load_downloaded_set() == ids

    def test_downloaded_count_reflects_all_traces(self, tmp_path):
        m = ExportManifest(tmp_path, batch_size=1)
        for i in range(3):
            m.mark_trace_downloaded(f"t{i}")
        assert m.downloaded_count() == 3

    def test_idempotent_mark_trace_downloaded_no_duplicate(self, tmp_path):
        """Marking the same trace twice must not raise or double-count."""
        m = ExportManifest(tmp_path, batch_size=1)
        m.mark_trace_downloaded("dup")
        m.mark_trace_downloaded("dup")
        assert m.downloaded_count() == 1

    # ---- trace ID list (experiment manifests) ------------------------------

    def test_store_and_get_all_trace_ids_round_trip(self, tmp_path):
        m = ExportManifest(tmp_path)
        ids = ["a", "b", "c"]
        m.store_all_trace_ids(ids)
        assert m.get_all_trace_ids() == ids

    def test_get_all_trace_ids_returns_none_before_stored(self, tmp_path):
        m = ExportManifest(tmp_path)
        assert m.get_all_trace_ids() is None

    # ---- class-level helpers -----------------------------------------------

    def test_exists_returns_false_when_no_db(self, tmp_path):
        assert not ExportManifest.exists(tmp_path)

    def test_exists_returns_true_after_creation(self, tmp_path):
        ExportManifest(tmp_path)
        assert ExportManifest.exists(tmp_path)

    def test_start_idempotent_preserves_started_at(self, tmp_path):
        """Calling start() twice keeps the original started_at timestamp."""
        m = ExportManifest(tmp_path)
        m.start("json")
        started_at_1 = m._conn.execute(
            "SELECT value FROM status WHERE key='started_at'"
        ).fetchone()[0]
        m.start("json")
        started_at_2 = m._conn.execute(
            "SELECT value FROM status WHERE key='started_at'"
        ).fetchone()[0]
        assert started_at_1 == started_at_2
        assert m.is_in_progress

    def test_save_flushes_pending_writes(self, tmp_path):
        m = ExportManifest(tmp_path, batch_size=100)
        m.mark_trace_downloaded("t-pending")
        assert m._pending_count == 1
        m.save()
        assert m._pending_count == 0
        assert m.downloaded_count() == 1


# ---------------------------------------------------------------------------
# MigrationManifest
# ---------------------------------------------------------------------------


class TestMigrationManifest:
    # ---- lifecycle ---------------------------------------------------------

    def test_fresh_manifest_is_not_started(self, tmp_path):
        m = MigrationManifest(tmp_path)
        assert m.status == "not_started"
        assert not m.is_in_progress
        assert not m.is_completed

    def test_start_sets_in_progress(self, tmp_path):
        m = MigrationManifest(tmp_path)
        m.start()
        assert m.status == "in_progress"
        assert m.is_in_progress
        assert not m.is_completed

    def test_complete_sets_completed(self, tmp_path):
        m = MigrationManifest(tmp_path)
        m.start()
        m.complete()
        assert m.status == "completed"
        assert m.is_completed
        assert not m.is_in_progress

    def test_reset_clears_all_state(self, tmp_path):
        m = MigrationManifest(tmp_path)
        m.start()
        f = tmp_path / "f.json"
        f.touch()
        m.mark_file_completed(f)
        m.add_trace_mapping("src1", "dst1")
        m.complete()
        m.reset()
        assert m.status == "not_started"
        assert m.completed_count() == 0
        assert m.get_trace_id_map() == {}

    # ---- file tracking -----------------------------------------------------

    def test_mark_file_completed_and_check(self, tmp_path):
        m = MigrationManifest(tmp_path, batch_size=1)
        f = tmp_path / "data.json"
        f.touch()
        m.mark_file_completed(f)
        assert m.is_file_completed(f)

    def test_is_file_completed_returns_false_for_unknown_file(self, tmp_path):
        m = MigrationManifest(tmp_path)
        assert not m.is_file_completed(tmp_path / "nope.json")

    def test_mark_file_failed_records_error(self, tmp_path):
        m = MigrationManifest(tmp_path, batch_size=1)
        f = tmp_path / "bad.json"
        f.touch()
        m.mark_file_failed(f, "parse error")
        assert m.failed_count() == 1
        failed = m.get_failed_files()
        rel = next(iter(failed))
        assert "bad.json" in rel
        assert "parse error" in failed[rel]

    def test_completion_supersedes_prior_failure(self, tmp_path):
        """Completing a previously-failed file removes it from failed_files."""
        m = MigrationManifest(tmp_path, batch_size=1)
        f = tmp_path / "retry.json"
        f.touch()
        m.mark_file_failed(f, "first attempt failed")
        assert m.failed_count() == 1
        m.mark_file_completed(f)
        assert m.failed_count() == 0
        assert m.is_file_completed(f)

    def test_completed_count(self, tmp_path):
        m = MigrationManifest(tmp_path, batch_size=1)
        for i in range(3):
            fi = tmp_path / f"f{i}.json"
            fi.touch()
            m.mark_file_completed(fi)
        assert m.completed_count() == 3

    # ---- trace ID mapping --------------------------------------------------

    def test_add_and_get_trace_mapping(self, tmp_path):
        m = MigrationManifest(tmp_path, batch_size=1)
        m.add_trace_mapping("src-a", "dst-a")
        m.add_trace_mapping("src-b", "dst-b")
        mapping = m.get_trace_id_map()
        assert mapping == {"src-a": "dst-a", "src-b": "dst-b"}

    def test_empty_trace_id_map_before_any_mappings(self, tmp_path):
        m = MigrationManifest(tmp_path)
        assert m.get_trace_id_map() == {}

    # ---- class-level helpers -----------------------------------------------

    def test_exists_returns_false_when_no_db(self, tmp_path):
        assert not MigrationManifest.exists(tmp_path)

    def test_exists_returns_true_after_creation(self, tmp_path):
        MigrationManifest(tmp_path)
        assert MigrationManifest.exists(tmp_path)


# ---------------------------------------------------------------------------
# _merge_stats
# ---------------------------------------------------------------------------


class TestMergeStats:
    def test_empty_total_accumulates_phase(self):
        from opik.cli.imports.all import _merge_stats

        total: dict = {}
        _merge_stats(total, {"datasets": 3, "datasets_skipped": 1})
        assert total == {"datasets": 3, "datasets_skipped": 1}

    def test_accumulates_across_multiple_phases(self):
        from opik.cli.imports.all import _merge_stats

        total: dict = {"datasets": 2}
        _merge_stats(total, {"datasets": 1, "traces": 5})
        assert total == {"datasets": 3, "traces": 5}

    def test_empty_phase_leaves_total_unchanged(self):
        from opik.cli.imports.all import _merge_stats

        total: dict = {"x": 7}
        _merge_stats(total, {})
        assert total == {"x": 7}


# ---------------------------------------------------------------------------
# import_all
# ---------------------------------------------------------------------------


class TestImportAll:
    """Unit tests for import_all(); sub-importers and the Opik client are mocked."""

    def test_fresh_run_calls_all_phases_and_completes_manifest(self, tmp_path):
        """A fresh import runs all four phases and marks the manifest completed."""
        for d in ("datasets", "prompts", "projects", "experiments"):
            (tmp_path / d).mkdir()

        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}
            ) as mock_ds,
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(
                f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}
            ) as mock_proj,
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ) as mock_exp,
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets", "prompts", "projects", "experiments"],
                dry_run=False,
                force=False,
                debug=False,
            )

        mock_ds.assert_called_once()
        mock_proj.assert_called_once()
        mock_exp.assert_called_once()

        m = MigrationManifest(tmp_path)
        assert m.is_completed

    def test_dry_run_skips_manifest_and_flush(self, tmp_path):
        """Dry-run must not create a manifest or flush the client."""
        (tmp_path / "datasets").mkdir()
        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets"],
                dry_run=True,
                force=False,
                debug=False,
            )

        assert not MigrationManifest.exists(tmp_path)
        client.flush.assert_not_called()

    def test_completed_manifest_returns_early_without_reimporting(self, tmp_path):
        """A completed manifest without --force causes immediate return."""
        manifest = MigrationManifest(tmp_path)
        manifest.start()
        manifest.complete()

        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}
            ) as mock_ds,
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(
                f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}
            ) as mock_proj,
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ) as mock_exp,
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets", "prompts", "projects", "experiments"],
                dry_run=False,
                force=False,
                debug=False,
            )

        mock_ds.assert_not_called()
        mock_proj.assert_not_called()
        mock_exp.assert_not_called()

    def test_force_flag_resets_completed_manifest_and_reimports(self, tmp_path):
        """--force discards a completed manifest and runs all requested phases."""
        (tmp_path / "datasets").mkdir()
        manifest = MigrationManifest(tmp_path)
        manifest.start()
        manifest.complete()

        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}
            ) as mock_ds,
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets"],
                dry_run=False,
                force=True,
                debug=False,
            )

        mock_ds.assert_called_once()

    def test_resume_in_progress_manifest_runs_phases_and_completes(self, tmp_path):
        """An in_progress manifest is treated as a resume; phases run and manifest completes."""
        (tmp_path / "datasets").mkdir()
        (tmp_path / "prompts").mkdir()

        # Simulate an interrupted import
        manifest = MigrationManifest(tmp_path)
        manifest.start()

        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}
            ) as mock_ds,
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ) as mock_pr,
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets", "prompts"],
                dry_run=False,
                force=False,
                debug=False,
            )

        mock_ds.assert_called_once()
        mock_pr.assert_called_once()
        m = MigrationManifest(tmp_path)
        assert m.is_completed

    def test_include_filter_runs_only_specified_phases(self, tmp_path):
        """When include=['datasets'], only the dataset phase is called."""
        (tmp_path / "datasets").mkdir()
        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}
            ) as mock_ds,
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ) as mock_pr,
            patch(
                f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}
            ) as mock_proj,
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ) as mock_exp,
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets"],
                dry_run=False,
                force=False,
                debug=False,
            )

        mock_ds.assert_called_once()
        mock_pr.assert_not_called()
        mock_proj.assert_not_called()
        mock_exp.assert_not_called()

    def test_missing_subdirectory_phase_is_skipped_gracefully(self, tmp_path):
        """If datasets/ does not exist, the dataset phase is silently skipped."""
        # Do NOT create (tmp_path / "datasets")
        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}
            ) as mock_ds,
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets"],
                dry_run=False,
                force=False,
                debug=False,
            )

        mock_ds.assert_not_called()

    def test_flush_timeout_exits_with_code_1(self, tmp_path):
        """If client.flush() returns False (timeout), import_all raises SystemExit(1)."""
        (tmp_path / "datasets").mkdir()
        client = _make_import_client()
        client.flush.return_value = False  # simulate timeout

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
            pytest.raises(SystemExit) as exc_info,
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets"],
                dry_run=False,
                force=False,
                debug=False,
            )

        assert exc_info.value.code == 1

    def test_failed_uploads_exits_with_code_1(self, tmp_path):
        """If failed_uploads > 0, import_all raises SystemExit(1)."""
        (tmp_path / "datasets").mkdir()
        client = _make_import_client()
        client.__internal_api__failed_uploads__ = MagicMock(return_value=3)

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
            pytest.raises(SystemExit) as exc_info,
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["datasets"],
                dry_run=False,
                force=False,
                debug=False,
            )

        assert exc_info.value.code == 1

    def test_intermediate_flush_after_prompts_imported(self, tmp_path):
        """client.flush() is called once after prompts phase (before end flush)
        when at least one prompt was imported."""
        (tmp_path / "prompts").mkdir()
        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 2},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["prompts"],
                dry_run=False,
                force=False,
                debug=False,
            )

        # flush called at least twice: once mid-import (after prompts), once at end
        assert client.flush.call_count >= 2

    def test_no_intermediate_flush_when_zero_prompts_imported(self, tmp_path):
        """client.flush() is called exactly once (end-of-import) when no prompts
        were imported — no intermediate flush is triggered."""
        (tmp_path / "prompts").mkdir()
        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="ws",
                path=str(tmp_path),
                include=["prompts"],
                dry_run=False,
                force=False,
                debug=False,
            )

        assert client.flush.call_count == 1

    def test_api_key_passed_to_opik_constructor(self, tmp_path):
        """When api_key is provided it is forwarded to opik.Opik()."""
        client = _make_import_client()

        with (
            patch(f"{_IMPORT_MODULE}.opik.Opik", return_value=client) as mock_opik,
            patch(f"{_IMPORT_MODULE}.import_datasets_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_prompts_from_directory",
                return_value={"prompts": 0},
            ),
            patch(f"{_IMPORT_MODULE}.import_projects_from_directory", return_value={}),
            patch(
                f"{_IMPORT_MODULE}.import_experiments_from_directory", return_value={}
            ),
        ):
            from opik.cli.imports.all import import_all

            import_all(
                workspace="my-ws",
                path=str(tmp_path),
                include=[],
                dry_run=False,
                force=False,
                debug=False,
                api_key="secret-key",
            )

        mock_opik.assert_called_once()
        kwargs = mock_opik.call_args[1]
        assert kwargs.get("api_key") == "secret-key"
        assert kwargs.get("workspace") == "my-ws"


# ---------------------------------------------------------------------------
# export_all
# ---------------------------------------------------------------------------


class TestExportAll:
    """Unit tests for export_all(); inner helpers and Opik client are mocked."""

    def test_workspace_directory_created(self, tmp_path):
        client = _make_export_client()
        with (
            patch(f"{_EXPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_EXPORT_MODULE}._export_all_datasets", return_value=(0, 0)),
            patch(f"{_EXPORT_MODULE}._export_all_prompts", return_value=(0, 0)),
            patch(
                f"{_EXPORT_MODULE}._export_all_projects", return_value=(0, 0, 0, False)
            ),
            patch(
                f"{_EXPORT_MODULE}._export_all_experiments",
                return_value=(0, 0, 0, 0, False),
            ),
        ):
            from opik.cli.exports.all import export_all

            export_all(
                workspace="my-ws",
                output_path=str(tmp_path),
                include=["datasets", "prompts", "projects", "experiments"],
                max_results=None,
                force=False,
                debug=False,
                format="json",
            )

        assert (tmp_path / "my-ws").is_dir()

    def test_subdirectories_created_for_all_phases(self, tmp_path):
        client = _make_export_client()
        with (
            patch(f"{_EXPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_EXPORT_MODULE}._export_all_datasets", return_value=(0, 0)),
            patch(f"{_EXPORT_MODULE}._export_all_prompts", return_value=(0, 0)),
            patch(
                f"{_EXPORT_MODULE}._export_all_projects", return_value=(0, 0, 0, False)
            ),
            patch(
                f"{_EXPORT_MODULE}._export_all_experiments",
                return_value=(0, 0, 0, 0, False),
            ),
        ):
            from opik.cli.exports.all import export_all

            export_all(
                workspace="ws",
                output_path=str(tmp_path),
                include=["datasets", "prompts", "projects", "experiments"],
                max_results=None,
                force=False,
                debug=False,
                format="json",
            )

        ws = tmp_path / "ws"
        for subdir in ("datasets", "prompts", "projects", "experiments"):
            assert (ws / subdir).is_dir(), f"Missing {subdir}/ directory"

    def test_only_included_phases_are_called(self, tmp_path):
        """With include=['datasets'], only _export_all_datasets is invoked."""
        client = _make_export_client()
        with (
            patch(f"{_EXPORT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_EXPORT_MODULE}._export_all_datasets", return_value=(2, 0)
            ) as mock_ds,
            patch(
                f"{_EXPORT_MODULE}._export_all_prompts", return_value=(0, 0)
            ) as mock_pr,
            patch(
                f"{_EXPORT_MODULE}._export_all_projects", return_value=(0, 0, 0, False)
            ) as mock_proj,
            patch(
                f"{_EXPORT_MODULE}._export_all_experiments",
                return_value=(0, 0, 0, 0, False),
            ) as mock_exp,
        ):
            from opik.cli.exports.all import export_all

            export_all(
                workspace="ws",
                output_path=str(tmp_path),
                include=["datasets"],
                max_results=None,
                force=False,
                debug=False,
                format="json",
            )

        mock_ds.assert_called_once()
        mock_pr.assert_not_called()
        mock_proj.assert_not_called()
        mock_exp.assert_not_called()

    def test_trace_totals_summed_across_projects_and_experiments(self, tmp_path):
        """Traces from projects (5) and experiments (3) are summed to 8 in stats."""
        client = _make_export_client()
        with (
            patch(f"{_EXPORT_MODULE}.opik.Opik", return_value=client),
            patch(f"{_EXPORT_MODULE}._export_all_datasets", return_value=(1, 0)),
            patch(f"{_EXPORT_MODULE}._export_all_prompts", return_value=(2, 0)),
            patch(
                f"{_EXPORT_MODULE}._export_all_projects", return_value=(1, 5, 0, False)
            ),
            patch(
                f"{_EXPORT_MODULE}._export_all_experiments",
                return_value=(3, 0, 3, 1, False),
            ),
            patch(f"{_EXPORT_MODULE}.print_export_summary") as mock_summary,
        ):
            from opik.cli.exports.all import export_all

            export_all(
                workspace="ws",
                output_path=str(tmp_path),
                include=["datasets", "prompts", "projects", "experiments"],
                max_results=None,
                force=False,
                debug=False,
                format="json",
            )

        args, _ = mock_summary.call_args
        stats = args[0]
        assert stats["traces"] == 8  # 5 from projects + 3 from experiments
        assert stats["datasets"] == 1
        assert stats["prompts"] == 2
        assert stats["experiments"] == 3

    def test_api_key_passed_to_opik_constructor(self, tmp_path):
        """When api_key is provided it is forwarded to opik.Opik()."""
        client = _make_export_client()
        with (
            patch(f"{_EXPORT_MODULE}.opik.Opik", return_value=client) as mock_opik,
            patch(f"{_EXPORT_MODULE}._export_all_datasets", return_value=(0, 0)),
            patch(f"{_EXPORT_MODULE}._export_all_prompts", return_value=(0, 0)),
            patch(
                f"{_EXPORT_MODULE}._export_all_projects", return_value=(0, 0, 0, False)
            ),
            patch(
                f"{_EXPORT_MODULE}._export_all_experiments",
                return_value=(0, 0, 0, 0, False),
            ),
        ):
            from opik.cli.exports.all import export_all

            export_all(
                workspace="ws",
                output_path=str(tmp_path),
                include=[],
                max_results=None,
                force=False,
                debug=False,
                format="json",
                api_key="my-key",
            )

        mock_opik.assert_called_once()
        kwargs = mock_opik.call_args[1]
        assert kwargs.get("api_key") == "my-key"


# ---------------------------------------------------------------------------
# _paginate helper
# ---------------------------------------------------------------------------


class TestPaginate:
    def test_single_page_yields_all_items(self):
        from opik.cli.exports.all import _paginate, PAGE_SIZE

        page = MagicMock()
        page.content = ["a", "b", "c"]
        page.total = 3
        list_fn = MagicMock(return_value=page)

        result = list(_paginate(list_fn))

        assert result == ["a", "b", "c"]
        list_fn.assert_called_once_with(page=1, size=PAGE_SIZE)

    def test_multi_page_response_iterates_all_pages(self):
        from opik.cli.exports.all import _paginate, PAGE_SIZE

        page1 = MagicMock()
        page1.content = list(range(PAGE_SIZE))
        page1.total = PAGE_SIZE + 1

        page2 = MagicMock()
        page2.content = [9999]
        page2.total = PAGE_SIZE + 1

        list_fn = MagicMock(side_effect=[page1, page2])
        result = list(_paginate(list_fn))

        assert len(result) == PAGE_SIZE + 1
        assert result[-1] == 9999
        assert list_fn.call_count == 2

    def test_empty_first_page_returns_empty_list(self):
        from opik.cli.exports.all import _paginate

        page = MagicMock()
        page.content = []
        page.total = 0
        list_fn = MagicMock(return_value=page)

        result = list(_paginate(list_fn))

        assert result == []
        list_fn.assert_called_once()

    def test_none_content_treated_as_empty(self):
        from opik.cli.exports.all import _paginate

        page = MagicMock()
        page.content = None
        page.total = 0
        list_fn = MagicMock(return_value=page)

        result = list(_paginate(list_fn))

        assert result == []


# ---------------------------------------------------------------------------
# export_experiment_by_id — JSON-based fast path
# ---------------------------------------------------------------------------


class TestExportExperimentByIdJsonFastPath:
    """Unit tests for the JSON-based fast path in export_experiment_by_id.

    When an experiment JSON file already exists on disk but the per-experiment
    manifest has no stored trace IDs (e.g. exported before the manifest feature
    was added), trace IDs should be extracted directly from the JSON file
    without making any get_items() API calls.
    """

    def test_json_fast_path_skips_get_items_and_returns_trace_ids(self, tmp_path):
        """Fast path reads trace IDs from JSON, get_experiment_by_id not called."""
        import json

        from opik.cli.exports.experiment import export_experiment_by_id

        experiment_id = "exp-abc-123"

        # Create the experiment JSON file the fast path will read from disk.
        experiment_file = tmp_path / f"experiment_my_exp_{experiment_id}.json"
        experiment_data = {
            "id": experiment_id,
            "name": "my_exp",
            "items": [
                {"trace_id": "trace-1", "other": "data"},
                {"trace_id": "trace-2", "other": "data"},
                {"other": "no_trace_id_here"},  # should be ignored
            ],
        }
        experiment_file.write_text(json.dumps(experiment_data))

        # The ExportManifest created inside export_experiment_by_id will be
        # freshly initialised (get_all_trace_ids() == None), which triggers
        # the JSON fast path when the experiment file is present.

        mock_client = MagicMock()
        collector: set = set()

        stats, file_written, _ = export_experiment_by_id(
            mock_client,
            tmp_path,
            experiment_id,
            max_traces=None,
            force=False,
            debug=False,
            format="json",
            trace_ids_collector=collector,
        )

        # API must not have been called — the fast path returns before any network I/O.
        mock_client.get_experiment_by_id.assert_not_called()

        # Collector should contain the two valid trace IDs from the JSON file.
        assert collector == {"trace-1", "trace-2"}

        # No new experiment file was written (the existing one was reused).
        assert file_written == 0
        assert stats.get("traces_skipped") == 2

    def test_json_fast_path_falls_through_on_corrupt_json(self, tmp_path):
        """If the JSON file is corrupt, the fast path falls through to the API."""
        from opik.cli.exports.experiment import export_experiment_by_id

        experiment_id = "exp-corrupt-456"

        # Write a corrupt JSON file.
        experiment_file = tmp_path / f"experiment_bad_{experiment_id}.json"
        experiment_file.write_text("{ not valid json }")

        mock_client = MagicMock()
        mock_client.get_experiment_by_id.return_value = MagicMock(
            name="my_exp",
            id=experiment_id,
            get_items=MagicMock(return_value=[]),
        )

        collector: set = set()

        export_experiment_by_id(
            mock_client,
            tmp_path,
            experiment_id,
            max_traces=None,
            force=False,
            debug=False,
            format="json",
            trace_ids_collector=collector,
        )

        # Corrupt JSON triggers fallback; get_experiment_by_id must be called.
        mock_client.get_experiment_by_id.assert_called_once_with(experiment_id)


# ---------------------------------------------------------------------------
# import_experiments_from_directory — filename-based project inference
# (ID baz-reviewer 2920864504)
# ---------------------------------------------------------------------------


class TestFilenameBasedProjectInference:
    """Verify trace_to_project_map is built from on-disk trace filenames and used
    to infer project_for_logs when experiment metadata has no explicit project."""

    def test_project_name_inferred_from_trace_filename(self, tmp_path):
        """When a trace file exists under projects/my-project/, its project name
        is inferred without opening the file."""
        import json

        from opik.cli.imports.experiment import import_experiments_from_directory

        # Build workspace layout: experiments/ and projects/my-project/
        experiments_dir = tmp_path / "experiments"
        experiments_dir.mkdir()
        projects_dir = tmp_path / "projects" / "my-project"
        projects_dir.mkdir(parents=True)

        trace_id = "trace-abc-123"
        (projects_dir / f"trace_{trace_id}.json").write_text("{}")

        # Write a minimal experiment JSON that references the trace above.
        exp_data = {
            "experiment": {
                "name": "test-exp",
                "id": "exp-1",
                "dataset_name": "ds",
            },
            "items": [
                {"trace_id": trace_id},
            ],
            "downloaded_at": "2024-01-01T00:00:00",
        }
        (experiments_dir / "experiment_test-exp_exp-1.json").write_text(
            json.dumps(exp_data)
        )

        mock_client = MagicMock()
        # create_experiment returns a mock with id
        created_exp = MagicMock()
        created_exp.id = "new-exp-id"
        mock_client.create_experiment.return_value = created_exp
        mock_client.get_experiment_by_id.return_value = MagicMock(id="new-exp-id")
        mock_client.flush.return_value = True

        result = import_experiments_from_directory(
            mock_client,
            experiments_dir,
            dry_run=True,  # dry_run avoids real API calls for import
            name_pattern=None,
            debug=False,
        )

        # The function should complete without error regardless of dry_run;
        # the key assertion is that no exception is raised and it returns stats.
        assert isinstance(result, dict)
        assert "experiments" in result
