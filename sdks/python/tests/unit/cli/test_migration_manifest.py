"""Tests for the migration manifest used by opik import for resumable migrations."""

import sqlite3
from pathlib import Path

import pytest

from opik.cli.migration_manifest import MigrationManifest, MANIFEST_FILENAME


@pytest.fixture
def tmp_base(tmp_path: Path) -> Path:
    return tmp_path


class TestMigrationManifestLifecycle:
    def test_manifest__fresh_instance__status_is_not_started(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        assert manifest.status == "not_started"
        assert not manifest.is_in_progress
        assert not manifest.is_completed

    def test_start__fresh_manifest__status_becomes_in_progress_and_file_written(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        assert manifest.is_in_progress
        assert (tmp_base / MANIFEST_FILENAME).exists()

    def test_complete__after_start__status_becomes_completed(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        manifest.complete()
        assert manifest.is_completed
        assert not manifest.is_in_progress

    def test_reset__after_start_with_data__all_state_cleared(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        trace_file = tmp_base / "projects" / "p1" / "trace_abc.json"
        trace_file.parent.mkdir(parents=True)
        trace_file.touch()
        manifest.mark_file_completed(trace_file)
        manifest.add_trace_mapping("src-1", "dest-1")

        manifest.reset()

        assert manifest.status == "not_started"
        assert manifest.completed_count() == 0
        assert manifest.get_trace_id_map() == {}

    def test_exists__before_any_save__returns_false(self, tmp_base: Path) -> None:
        assert not MigrationManifest.exists(tmp_base)

    def test_exists__after_start__returns_true(self, tmp_base: Path) -> None:
        MigrationManifest(tmp_base).start()
        assert MigrationManifest.exists(tmp_base)

    def test_start__called_twice__started_at_preserved(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()

        conn = sqlite3.connect(str(tmp_base / MANIFEST_FILENAME))
        first_started_at = conn.execute(
            "SELECT value FROM status WHERE key = 'started_at'"
        ).fetchone()[0]
        conn.close()

        manifest.start()  # second call must not overwrite started_at

        conn = sqlite3.connect(str(tmp_base / MANIFEST_FILENAME))
        second_started_at = conn.execute(
            "SELECT value FROM status WHERE key = 'started_at'"
        ).fetchone()[0]
        conn.close()

        assert second_started_at == first_started_at


class TestFileTracking:
    def _make_file(self, base: Path, relative: str) -> Path:
        p = base / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.touch()
        return p

    def test_is_file_completed__fresh_manifest__returns_false(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "datasets/dataset_foo.json")
        assert not manifest.is_file_completed(f)

    def test_mark_file_completed__new_file__recorded_and_count_incremented(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "datasets/dataset_foo.json")
        manifest.mark_file_completed(f)
        assert manifest.is_file_completed(f)
        assert manifest.completed_count() == 1

    def test_mark_file_completed__same_file_twice__count_remains_one(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "datasets/dataset_foo.json")
        manifest.mark_file_completed(f)
        manifest.mark_file_completed(f)
        assert manifest.completed_count() == 1

    def test_mark_file_failed__new_file__recorded_with_error(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "projects/p1/trace_abc.json")
        manifest.mark_file_failed(f, "timeout")
        assert manifest.failed_count() == 1
        assert manifest.get_failed_files()[manifest.relative_path(f)] == "timeout"

    def test_mark_file_completed__previously_failed_file__removed_from_failed(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "projects/p1/trace_abc.json")
        manifest.mark_file_failed(f, "timeout")
        manifest.mark_file_completed(f)
        assert manifest.failed_count() == 0
        assert manifest.is_file_completed(f)

    def test_relative_path__nested_file__returns_path_relative_to_base(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "projects/my-project/trace_xyz.json")
        assert manifest.relative_path(f) == str(
            Path("projects") / "my-project" / "trace_xyz.json"
        )


class TestTraceIdMapping:
    def test_add_trace_mapping__single_entry__retrievable_via_get_map(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.add_trace_mapping("src-111", "dest-222")
        manifest.save()
        assert manifest.get_trace_id_map() == {"src-111": "dest-222"}

    def test_trace_id_map__save_then_new_instance__mapping_survives(
        self, tmp_base: Path
    ) -> None:
        m1 = MigrationManifest(tmp_base)
        m1.start()
        m1.add_trace_mapping("src-aaa", "dest-bbb")
        m1.save()

        m2 = MigrationManifest(tmp_base)
        assert m2.get_trace_id_map() == {"src-aaa": "dest-bbb"}

    def test_get_trace_id_map__mutate_returned_dict__original_unaffected(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.add_trace_mapping("src-1", "dest-1")
        manifest.save()
        copy = manifest.get_trace_id_map()
        copy["src-2"] = "dest-2"  # mutate the copy
        assert "src-2" not in manifest.get_trace_id_map()

    def test_add_trace_mapping__same_src_id_twice__last_dest_wins(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.add_trace_mapping("src-1", "dest-old")
        manifest.add_trace_mapping("src-1", "dest-new")
        manifest.save()
        assert manifest.get_trace_id_map()["src-1"] == "dest-new"


class TestDatabaseIntegrity:
    def test_manifest_file__after_start_and_save__is_valid_sqlite_with_expected_tables(
        self, tmp_base: Path
    ) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        manifest.add_trace_mapping("a", "b")
        manifest.save()

        conn = sqlite3.connect(str(tmp_base / MANIFEST_FILENAME))
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        conn.close()

        assert {"status", "completed_files", "failed_files", "trace_id_map"} <= tables

    def test_save__after_start__no_tmp_file_created(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        assert not (tmp_base / "migration_manifest.tmp").exists()

    def test_mark_file_completed__duplicate_write__count_remains_one(
        self, tmp_base: Path
    ) -> None:
        """INSERT OR IGNORE means re-flushing the same path never duplicates rows."""
        manifest = MigrationManifest(tmp_base, batch_size=1)
        f = tmp_base / "projects" / "p" / "trace_x.json"
        f.parent.mkdir(parents=True)
        f.touch()
        manifest.mark_file_completed(f)
        manifest.mark_file_completed(f)
        assert manifest.completed_count() == 1


class TestBatchingBehavior:
    """Document and verify the crash trade-off introduced by batched writes."""

    def _make_trace(self, base: Path, name: str) -> Path:
        p = base / "projects" / "proj" / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.touch()
        return p

    def test_mark_file_completed__within_batch__visible_via_api_before_flush(
        self, tmp_base: Path
    ) -> None:
        """Buffered completions are visible through the public API (which flushes
        before querying) even before the batch threshold is reached."""
        manifest = MigrationManifest(tmp_base, batch_size=50)
        f = self._make_trace(tmp_base, "trace_001.json")
        manifest.mark_file_completed(f)
        # completed_count() flushes first, so the buffered write IS visible.
        assert manifest.completed_count() == 1
        assert manifest.is_file_completed(f)

    def test_mark_file_completed__crash_before_flush__unflushed_data_lost(
        self, tmp_base: Path
    ) -> None:
        """Simulates a crash (new instance, no save()) with batch_size > pending count.

        This is the documented trade-off: up to batch_size-1 completions may be
        absent from the manifest after a crash. Those files will simply be
        re-imported on resume.
        """
        f = self._make_trace(tmp_base, "trace_001.json")

        m1 = MigrationManifest(tmp_base, batch_size=50)
        m1.start()  # always flushes immediately
        m1.mark_file_completed(f)  # buffered — NOT yet on disk
        # Simulate crash: m1 is abandoned without save() or complete().
        # __del__ is NOT called here because m1 is still in scope when m2 is created.

        m2 = MigrationManifest(tmp_base, batch_size=50)
        # The completion is not on disk — resume will re-process this file.
        assert not m2.is_file_completed(f)
        assert m2.completed_count() == 0

    def test_mark_file_completed__batch_size_one__each_write_immediately_durable(
        self, tmp_base: Path
    ) -> None:
        """With batch_size=1 every completion is immediately committed to disk."""
        f = self._make_trace(tmp_base, "trace_001.json")

        m1 = MigrationManifest(tmp_base, batch_size=1)
        m1.start()
        m1.mark_file_completed(f)  # auto-flushes (batch_size=1)

        # New instance reads from disk — must see the completion.
        m2 = MigrationManifest(tmp_base, batch_size=1)
        assert m2.is_file_completed(f)
        assert m2.completed_count() == 1

    def test_mark_file_completed__batch_threshold_reached__auto_flushed_to_disk(
        self, tmp_base: Path
    ) -> None:
        """When pending count hits batch_size the buffer is auto-flushed."""
        batch_size = 3
        files = [self._make_trace(tmp_base, f"trace_{i:03d}.json") for i in range(3)]

        m1 = MigrationManifest(tmp_base, batch_size=batch_size)
        m1.start()
        for f in files:
            m1.mark_file_completed(f)  # third call triggers auto-flush

        # New instance must see all three completions.
        m2 = MigrationManifest(tmp_base, batch_size=batch_size)
        assert m2.completed_count() == 3
        for f in files:
            assert m2.is_file_completed(f)


class TestResumeScenario:
    """Simulate a real interrupted + resumed migration."""

    def test_resume__interrupted_import__completed_files_skipped_and_id_map_available(
        self, tmp_base: Path
    ) -> None:
        """After an interrupted run the second run skips already-flushed files.

        batch_size=1 so every mark_file_completed is immediately durable —
        this models the boundary between two process invocations where only
        flushed data survives.
        """
        trace_files = []
        for i in range(3):
            p = tmp_base / "projects" / "proj" / f"trace_{i:03d}.json"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.touch()
            trace_files.append(p)

        m1 = MigrationManifest(tmp_base, batch_size=1)
        m1.start()
        m1.add_trace_mapping("src-0", "dest-0")
        m1.mark_file_completed(trace_files[0])
        m1.add_trace_mapping("src-1", "dest-1")
        m1.mark_file_completed(trace_files[1])
        # Process crashes before trace_files[2] — no complete() call.

        m2 = MigrationManifest(tmp_base)
        assert m2.is_in_progress
        assert m2.completed_count() == 2
        assert m2.is_file_completed(trace_files[0])
        assert m2.is_file_completed(trace_files[1])
        assert not m2.is_file_completed(trace_files[2])

        id_map = m2.get_trace_id_map()
        assert id_map["src-0"] == "dest-0"
        assert id_map["src-1"] == "dest-1"
        assert "src-2" not in id_map

    def test_reset__after_completed_files__all_state_cleared(
        self, tmp_base: Path
    ) -> None:
        trace_file = tmp_base / "projects" / "p" / "trace_abc.json"
        trace_file.parent.mkdir(parents=True)
        trace_file.touch()

        m1 = MigrationManifest(tmp_base)
        m1.start()
        m1.mark_file_completed(trace_file)

        m2 = MigrationManifest(tmp_base)
        m2.reset()

        assert m2.completed_count() == 0
        assert not m2.is_file_completed(trace_file)
        assert m2.status == "not_started"
