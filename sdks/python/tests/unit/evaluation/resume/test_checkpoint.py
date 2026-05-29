import json
from pathlib import Path
from unittest import mock

import pytest

from opik.evaluation.resume import checkpoint


@pytest.fixture
def isolated_checkpoint_dir(tmp_path, monkeypatch):
    """Redirect the on-disk checkpoint dir to a temp path for the test."""
    monkeypatch.setattr(checkpoint, "LOCAL_CHECKPOINT_DIR", tmp_path)
    return tmp_path


class TestWriteCheckpoint:
    def test_writes_payload_with_ids_and_schema(self, isolated_checkpoint_dir):
        path = checkpoint.write_checkpoint("exp-1", ["a", "b", "c"])

        assert path == isolated_checkpoint_dir / "exp-1.json"
        payload = json.loads(path.read_text())
        assert payload["schema_version"] == checkpoint.CHECKPOINT_SCHEMA_VERSION
        assert payload["experiment_id"] == "exp-1"
        assert payload["resolved_dataset_item_ids"] == ["a", "b", "c"]

    def test_creates_parent_directory_if_missing(self, tmp_path, monkeypatch):
        nested = tmp_path / "deep" / "nested" / "dir"
        monkeypatch.setattr(checkpoint, "LOCAL_CHECKPOINT_DIR", nested)

        checkpoint.write_checkpoint("exp-1", ["a"])

        assert (nested / "exp-1.json").exists()

    def test_overwrite__replaces_previous_content(self, isolated_checkpoint_dir):
        checkpoint.write_checkpoint("exp-1", ["a", "b"])
        checkpoint.write_checkpoint("exp-1", ["c"])

        assert checkpoint.read_checkpoint("exp-1") == ["c"]


class TestReadCheckpoint:
    def test_missing_file__returns_none(self, isolated_checkpoint_dir):
        assert checkpoint.read_checkpoint("exp-1") is None

    def test_round_trip__returns_same_ids(self, isolated_checkpoint_dir):
        checkpoint.write_checkpoint("exp-1", ["id-1", "id-2"])

        assert checkpoint.read_checkpoint("exp-1") == ["id-1", "id-2"]

    def test_malformed_json__returns_none(self, isolated_checkpoint_dir):
        target = isolated_checkpoint_dir / "exp-1.json"
        target.write_text("{not valid json")

        assert checkpoint.read_checkpoint("exp-1") is None

    def test_unexpected_payload_shape__returns_none(self, isolated_checkpoint_dir):
        target = isolated_checkpoint_dir / "exp-1.json"
        target.write_text(json.dumps({"resolved_dataset_item_ids": "not-a-list"}))

        assert checkpoint.read_checkpoint("exp-1") is None

    def test_payload_with_non_string_ids__returns_none(
        self, isolated_checkpoint_dir
    ):
        target = isolated_checkpoint_dir / "exp-1.json"
        target.write_text(json.dumps({"resolved_dataset_item_ids": [1, 2, 3]}))

        assert checkpoint.read_checkpoint("exp-1") is None


class TestDeleteCheckpoint:
    def test_removes_existing_file(self, isolated_checkpoint_dir):
        checkpoint.write_checkpoint("exp-1", ["a"])

        checkpoint.delete_checkpoint("exp-1")

        assert checkpoint.read_checkpoint("exp-1") is None

    def test_missing_file__no_error(self, isolated_checkpoint_dir):
        # Should be silent — never raise
        checkpoint.delete_checkpoint("not-there")


class TestCheckpointPath:
    def test_returns_path_under_checkpoint_dir(self, isolated_checkpoint_dir):
        assert (
            checkpoint.checkpoint_path("exp-1")
            == isolated_checkpoint_dir / "exp-1.json"
        )
