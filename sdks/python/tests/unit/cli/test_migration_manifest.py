"""Tests for the migration manifest used by opik import for resumable migrations."""

import sqlite3
from pathlib import Path

import pytest

from opik.cli.migration_manifest import MigrationManifest, MANIFEST_FILENAME


@pytest.fixture
def tmp_base(tmp_path: Path) -> Path:
    return tmp_path


class TestMigrationManifestLifecycle:
    def test_creates_manifest_on_first_use(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        assert manifest.status == "not_started"
        assert not manifest.is_in_progress
        assert not manifest.is_completed

    def test_start_marks_in_progress_and_writes_file(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        assert manifest.is_in_progress
        assert (tmp_base / MANIFEST_FILENAME).exists()

    def test_complete_marks_completed(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        manifest.complete()
        assert manifest.is_completed
        assert not manifest.is_in_progress

    def test_reset_clears_all_state(self, tmp_base: Path) -> None:
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

    def test_exists_returns_false_before_first_save(self, tmp_base: Path) -> None:
        assert not MigrationManifest.exists(tmp_base)

    def test_exists_returns_true_after_start(self, tmp_base: Path) -> None:
        # The DB file is created when the manifest is opened, but we check
        # that exists() reflects the on-disk file correctly.
        MigrationManifest(tmp_base).start()
        assert MigrationManifest.exists(tmp_base)

    def test_start_is_idempotent_preserves_started_at(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()

        # Read started_at directly from the SQLite DB.
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

    def test_file_not_completed_initially(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "datasets/dataset_foo.json")
        assert not manifest.is_file_completed(f)

    def test_mark_file_completed(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "datasets/dataset_foo.json")
        manifest.mark_file_completed(f)
        assert manifest.is_file_completed(f)
        assert manifest.completed_count() == 1

    def test_mark_file_completed_is_idempotent(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "datasets/dataset_foo.json")
        manifest.mark_file_completed(f)
        manifest.mark_file_completed(f)
        assert manifest.completed_count() == 1

    def test_mark_file_failed(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "projects/p1/trace_abc.json")
        manifest.mark_file_failed(f, "timeout")
        assert manifest.failed_count() == 1
        assert manifest.get_failed_files()[manifest.relative_path(f)] == "timeout"

    def test_mark_completed_clears_failed(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "projects/p1/trace_abc.json")
        manifest.mark_file_failed(f, "timeout")
        manifest.mark_file_completed(f)
        assert manifest.failed_count() == 0
        assert manifest.is_file_completed(f)

    def test_relative_path_uses_base_path(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        f = self._make_file(tmp_base, "projects/my-project/trace_xyz.json")
        assert manifest.relative_path(f) == str(
            Path("projects") / "my-project" / "trace_xyz.json"
        )


class TestTraceIdMapping:
    def test_add_and_retrieve_trace_mapping(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.add_trace_mapping("src-111", "dest-222")
        manifest.save()
        assert manifest.get_trace_id_map() == {"src-111": "dest-222"}

    def test_trace_id_map_persists_across_instances(self, tmp_base: Path) -> None:
        m1 = MigrationManifest(tmp_base)
        m1.start()
        m1.add_trace_mapping("src-aaa", "dest-bbb")
        m1.save()

        m2 = MigrationManifest(tmp_base)
        assert m2.get_trace_id_map() == {"src-aaa": "dest-bbb"}

    def test_get_trace_id_map_returns_copy(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.add_trace_mapping("src-1", "dest-1")
        manifest.save()
        copy = manifest.get_trace_id_map()
        copy["src-2"] = "dest-2"  # mutate the copy
        assert "src-2" not in manifest.get_trace_id_map()


class TestDatabaseIntegrity:
    def test_manifest_file_is_valid_sqlite(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        manifest.add_trace_mapping("a", "b")
        manifest.save()

        # Verify the file is a readable SQLite database with the expected tables.
        conn = sqlite3.connect(str(tmp_base / MANIFEST_FILENAME))
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        conn.close()

        assert {"status", "completed_files", "failed_files", "trace_id_map"} <= tables

    def test_no_tmp_file_left_after_save(self, tmp_base: Path) -> None:
        manifest = MigrationManifest(tmp_base)
        manifest.start()
        # SQLite WAL mode leaves a -wal and -shm file while the connection is
        # open, but never a .tmp artefact like the old JSON approach.
        assert not (tmp_base / "migration_manifest.tmp").exists()

    def test_duplicate_completed_writes_are_idempotent(self, tmp_base: Path) -> None:
        """INSERT OR IGNORE means re-flushing the same path never duplicates rows."""
        manifest = MigrationManifest(tmp_base, batch_size=1)
        f = tmp_base / "projects" / "p" / "trace_x.json"
        f.parent.mkdir(parents=True)
        f.touch()
        manifest.mark_file_completed(f)  # flushes (batch_size=1)
        manifest.mark_file_completed(f)  # flushes again — must not create duplicate
        assert manifest.completed_count() == 1

    def test_duplicate_trace_mappings_last_write_wins(self, tmp_base: Path) -> None:
        """INSERT OR REPLACE means updating a mapping overwrites the old value."""
        manifest = MigrationManifest(tmp_base)
        manifest.add_trace_mapping("src-1", "dest-old")
        manifest.add_trace_mapping("src-1", "dest-new")  # same src, new dest
        manifest.save()
        assert manifest.get_trace_id_map()["src-1"] == "dest-new"


class TestResumeScenario:
    """Simulate a real interrupted + resumed migration."""

    def test_resume_skips_completed_files(self, tmp_base: Path) -> None:
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

        # Second run loads the same manifest.
        m2 = MigrationManifest(tmp_base)
        assert m2.is_in_progress  # correctly detects interrupted state
        assert m2.completed_count() == 2
        assert m2.is_file_completed(trace_files[0])
        assert m2.is_file_completed(trace_files[1])
        assert not m2.is_file_completed(trace_files[2])  # must still be processed

        # The trace_id_map from the first run is available for experiment linking.
        id_map = m2.get_trace_id_map()
        assert id_map["src-0"] == "dest-0"
        assert id_map["src-1"] == "dest-1"
        assert "src-2" not in id_map

    def test_force_resets_progress(self, tmp_base: Path) -> None:
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
