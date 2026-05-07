"""Tests for ``opik migrate`` (slice 1: dataset + workspace plan)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional
from unittest.mock import MagicMock, patch

import pytest
from click.testing import CliRunner

from opik.cli import cli
from opik.cli.migrate import planner as planner_module
from opik.cli.migrate.errors import (
    AmbiguityError,
    ConflictError,
    DatasetNotFoundError,
    ProjectNotFoundError,
)


def _planner_rest_client(
    find_side_effects: List["_Page"],
    *,
    target_project_exists: bool = True,
    workspace_project_names: Optional[List[str]] = None,
) -> MagicMock:
    """Build a rest_client mock for direct planner unit tests."""
    rest_client = MagicMock()
    if target_project_exists:
        target_project = MagicMock()
        target_project.id = "target-project-id"
        rest_client.projects.retrieve_project.return_value = target_project
    else:
        from opik.rest_api.core.api_error import ApiError

        rest_client.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={}
        )
        # Suggestion lookup queries find_projects on the 404 path.
        candidates = [_named(name) for name in (workspace_project_names or [])]
        rest_client.projects.find_projects.return_value = _Page(candidates)
    rest_client.datasets.find_datasets.side_effect = find_side_effects
    return rest_client


def _named(name: str) -> MagicMock:
    obj = MagicMock()
    obj.name = name
    return obj


class _DatasetRow:
    def __init__(
        self,
        id: str,
        name: str,
        description: Optional[str] = None,
        items: int = 0,
    ) -> None:
        self.id = id
        self.name = name
        self.description = description
        self.dataset_items_count = items


class _Page:
    def __init__(self, content: List[_DatasetRow]) -> None:
        self.content = content


def _build_fake_client(
    *,
    source_rows: List[_DatasetRow],
    destination_rows: List[_DatasetRow],
    items: List[Dict[str, object]],
    target_project_exists: bool = True,
) -> MagicMock:
    """Construct an opik.Opik mock matching the executor's call surface.

    The find_datasets mock returns ``source_rows`` for the first lookup
    (source resolution) and ``destination_rows`` for the second (preflight
    conflict check). The planner places those calls in a fixed order, so a
    side-effect list is the simplest way to drive both branches deterministically.
    """
    rest_client = MagicMock()
    if target_project_exists:
        target_project = MagicMock()
        target_project.id = "target-project-id"
        rest_client.projects.retrieve_project.return_value = target_project
    else:
        from opik.rest_api.core.api_error import ApiError

        rest_client.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={}
        )
    rest_client.datasets.find_datasets.side_effect = [
        _Page(source_rows),
        _Page(destination_rows),
    ]
    rest_client.datasets.update_dataset = MagicMock()

    client = MagicMock()
    client.rest_client = rest_client

    source_dataset = MagicMock()
    source_dataset.get_items.return_value = items
    dest_dataset = MagicMock()

    def _get_dataset(name: str, project_name: Optional[str] = None) -> MagicMock:
        # The executor first reads from the (renamed) source, then the dest.
        if dest_dataset.insert.call_count == 0 and not getattr(
            _get_dataset, "_dest_handed_out", False
        ):
            if source_dataset.get_items.call_count == 0:
                return source_dataset
            _get_dataset._dest_handed_out = True  # type: ignore[attr-defined]
            return dest_dataset
        return dest_dataset

    client.get_dataset.side_effect = _get_dataset
    client.create_dataset = MagicMock()
    client.delete_dataset = MagicMock()
    return client, source_dataset, dest_dataset


@pytest.fixture(autouse=True)
def _no_project_resolution() -> None:
    """``--from-project=None`` always yields ``project_id=None``; no need to mock the projects API."""
    yield


# ---------------------------------------------------------------------------
# Help text
# ---------------------------------------------------------------------------


class TestMigrateHelp:
    def test_migrate_group_help(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "--help"])
        assert result.exit_code == 0
        assert "Migrate Opik entities" in result.output
        assert "dataset" in result.output
        assert "plan" in result.output

    def test_dataset_help_lists_required_flags(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "dataset", "--help"])
        assert result.exit_code == 0
        assert "--to-project" in result.output
        assert "--from-project" in result.output
        assert "--target-name" in result.output
        assert "--source-suffix" in result.output
        assert "--delete-source" in result.output
        assert "--dry-run" in result.output

    def test_plan_help(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "plan", "--help"])
        assert result.exit_code == 0
        assert "Survey the workspace" in result.output


# ---------------------------------------------------------------------------
# Planner unit tests (no Click invocation)
# ---------------------------------------------------------------------------


class TestPlanBuilding:
    def test_plan_orders_rename_create_copy(self) -> None:
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset", description="d")]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MyDataset",
            to_project="B",
            from_project=None,
            target_name=None,
            source_suffix="_v1",
            delete_source=False,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == ["RenameSource", "CreateDestination", "CopyCurrentItems"]
        rename = plan.actions[0]
        assert rename.from_name == "MyDataset"
        assert rename.to_name == "MyDataset_v1"
        assert plan.target_name == "MyDataset"

    def test_target_name_skips_rename(self) -> None:
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MyDataset",
            to_project="B",
            from_project=None,
            target_name="RenamedInDest",
            source_suffix="_v1",
            delete_source=False,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == ["CreateDestination", "CopyCurrentItems"]
        assert plan.target_name == "RenamedInDest"

    def test_delete_source_is_last_action(self) -> None:
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MyDataset",
            to_project="B",
            from_project=None,
            target_name=None,
            source_suffix="_v1",
            delete_source=True,
        )

        assert type(plan.actions[-1]).__name__ == "DeleteSource"

    def test_post_rename_name_collision_raises(self) -> None:
        # Default flow: source rename frees the target name, but the
        # post-rename name "<source>_v1" itself collides with another dataset
        # in the workspace.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([_DatasetRow(id="other-1", name="MyDataset_v1")]),
            ]
        )

        with pytest.raises(ConflictError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="MyDataset",
                to_project="B",
                from_project=None,
                target_name=None,
                source_suffix="_v1",
                delete_source=False,
            )
        assert "MyDataset_v1" in str(exc_info.value)
        assert "--source-suffix" in str(exc_info.value)

    def test_target_name_workspace_collision_raises(self) -> None:
        # --target-name=Other, but Other already exists somewhere in the workspace.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([_DatasetRow(id="other-1", name="Other")]),
            ]
        )

        with pytest.raises(ConflictError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="MyDataset",
                to_project="B",
                from_project=None,
                target_name="Other",
                source_suffix="_v1",
                delete_source=False,
            )
        assert "Other" in str(exc_info.value)

    def test_post_rename_collision_ignores_source_itself(self) -> None:
        # When find_datasets returns the source itself, we must not treat that
        # as a collision — it's about to be renamed.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([_DatasetRow(id="src-1", name="MyDataset_v1")]),
            ]
        )
        # Should NOT raise: the only "match" is the source dataset itself.
        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MyDataset",
            to_project="B",
            from_project=None,
            target_name=None,
            source_suffix="_v1",
            delete_source=False,
        )
        assert plan.target_name == "MyDataset"

    def test_unknown_name_raises(self) -> None:
        rest_client = _planner_rest_client([_Page([])])

        with pytest.raises(DatasetNotFoundError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="Missing",
                to_project="B",
                from_project=None,
                target_name=None,
                source_suffix="_v1",
                delete_source=False,
            )
        assert "Missing" in str(exc_info.value)

    def test_ambiguous_name_raises(self) -> None:
        rest_client = _planner_rest_client(
            [
                _Page(
                    [
                        _DatasetRow(id="a", name="MyDataset"),
                        _DatasetRow(id="b", name="MyDataset"),
                    ]
                )
            ]
        )

        with pytest.raises(AmbiguityError):
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="MyDataset",
                to_project="B",
                from_project=None,
                target_name=None,
                source_suffix="_v1",
                delete_source=False,
            )

    def test_destination_project_missing_raises(self) -> None:
        rest_client = _planner_rest_client(
            find_side_effects=[],
            target_project_exists=False,
        )

        with pytest.raises(ProjectNotFoundError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="MyDataset",
                to_project="DoesNotExist",
                from_project=None,
                target_name=None,
                source_suffix="_v1",
                delete_source=False,
            )
        assert "DoesNotExist" in str(exc_info.value)

    def test_destination_project_missing_suggests_similar(self) -> None:
        rest_client = _planner_rest_client(
            find_side_effects=[],
            target_project_exists=False,
            workspace_project_names=["production", "staging", "Beat", "Best"],
        )

        with pytest.raises(ProjectNotFoundError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="MyDataset",
                to_project="Beta",
                from_project=None,
                target_name=None,
                source_suffix="_v1",
                delete_source=False,
            )
        message = str(exc_info.value)
        assert "Beta" in message
        # difflib should surface the close one-letter neighbours.
        assert "Did you mean" in message
        assert "Beat" in message or "Best" in message


# ---------------------------------------------------------------------------
# End-to-end CLI behavior (with mocked opik.Opik)
# ---------------------------------------------------------------------------


class TestMigrateDatasetCommand:
    def _invoke(
        self,
        runner: CliRunner,
        args: List[str],
        client: MagicMock,
        tmp_path: Path,
    ):
        audit_path = tmp_path / "audit.json"
        with patch("opik.cli.migrate.main.opik.Opik", return_value=client):
            return runner.invoke(
                cli,
                ["migrate", "dataset", *args, "--audit-log", str(audit_path)],
            )

    def test_success_path_in_order(self, tmp_path: Path) -> None:
        runner = CliRunner()
        client, source_dataset, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset", description="d")],
            destination_rows=[],
            items=[{"id": "i1", "input": "hello"}, {"id": "i2", "input": "world"}],
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B"],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        # Rename via REST update
        client.rest_client.datasets.update_dataset.assert_called_once_with(
            id="src-1", name="MyDataset_v1"
        )
        # Destination created
        client.create_dataset.assert_called_once()
        # Items copied (id stripped) in one insert() call → one target version.
        dest_dataset.insert.assert_called_once()
        inserted = dest_dataset.insert.call_args.args[0]
        assert all("id" not in item for item in inserted)
        assert len(inserted) == 2
        # No delete
        client.delete_dataset.assert_not_called()

        # Audit log written and parses
        audit = json.loads((tmp_path / "audit.json").read_text())
        assert audit["schema_version"] == 1
        assert audit["status"] == "ok"
        types = [a["type"] for a in audit["actions"]]
        assert "rename_source" in types
        assert "create_destination" in types
        assert "copy_dataset_items" in types

    def test_destination_conflict_no_side_effects(self, tmp_path: Path) -> None:
        runner = CliRunner()
        # Workspace already has a dataset named "MyDataset_v1" (the post-rename
        # target name) — pre-flight should reject before any side effect.
        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[_DatasetRow(id="other-1", name="MyDataset_v1")],
            items=[],
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B"],
            client,
            tmp_path,
        )

        assert result.exit_code == 1
        client.rest_client.datasets.update_dataset.assert_not_called()
        client.create_dataset.assert_not_called()
        dest_dataset.insert.assert_not_called()
        client.delete_dataset.assert_not_called()

        audit = json.loads((tmp_path / "audit.json").read_text())
        assert audit["status"] == "failed"

    def test_dry_run_writes_no_data(self, tmp_path: Path) -> None:
        runner = CliRunner()
        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "i1", "x": 1}],
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B", "--dry-run"],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        client.rest_client.datasets.update_dataset.assert_not_called()
        client.create_dataset.assert_not_called()
        dest_dataset.insert.assert_not_called()
        client.delete_dataset.assert_not_called()

        audit = json.loads((tmp_path / "audit.json").read_text())
        assert audit["status"] == "planned"
        assert all(a["status"] == "planned" for a in audit["actions"])

    def test_target_name_skips_rename(self, tmp_path: Path) -> None:
        runner = CliRunner()
        client, _, _ = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[],
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B", "--target-name", "Renamed"],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        client.rest_client.datasets.update_dataset.assert_not_called()
        # Destination created with the override name
        kwargs = client.create_dataset.call_args.kwargs
        assert kwargs["name"] == "Renamed"

    def test_delete_source_runs_only_after_success(self, tmp_path: Path) -> None:
        runner = CliRunner()
        client, _, _ = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "i1", "x": 1}],
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B", "--delete-source"],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        client.delete_dataset.assert_called_once()
        # Verify ordering: delete is recorded after copy in the audit log
        audit = json.loads((tmp_path / "audit.json").read_text())
        ok_types = [a["type"] for a in audit["actions"] if a["status"] == "ok"]
        assert ok_types.index("copy_dataset_items") < ok_types.index("delete_source")

    def test_delete_source_skipped_on_copy_failure(self, tmp_path: Path) -> None:
        runner = CliRunner()
        client, source_dataset, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "i1", "x": 1}],
        )
        dest_dataset.insert.side_effect = RuntimeError("boom")

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B", "--delete-source"],
            client,
            tmp_path,
        )

        assert result.exit_code == 1
        client.delete_dataset.assert_not_called()
        audit = json.loads((tmp_path / "audit.json").read_text())
        assert audit["status"] == "failed"
        assert not any(a["type"] == "delete_source" for a in audit["actions"])

    def test_items_inserted_in_reverse_of_source_order(self, tmp_path: Path) -> None:
        # get_items() returns newest-first; the executor reverses before insert
        # so the target's display order matches the source's display order.
        runner = CliRunner()
        items = [{"id": f"i{i}", "idx": i} for i in (5, 4, 3, 2, 1)]
        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=items,
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B"],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        inserted = dest_dataset.insert.call_args.args[0]
        # Source returned [5,4,3,2,1]; insert receives [1,2,3,4,5].
        assert [item["idx"] for item in inserted] == [1, 2, 3, 4, 5]

    def test_copy_calls_insert_exactly_once_for_one_target_version(
        self, tmp_path: Path
    ) -> None:
        # Critical contract: even with many items, the executor must call
        # Dataset.insert() ONCE — that's how we get a single dataset version
        # on the target. Calling insert() in a loop would create one version
        # per call, which would (a) violate slice 1's "current items only"
        # promise and (b) corrupt the starting state for slice 2's version-
        # history replay.
        runner = CliRunner()
        n = 5_000  # comfortably larger than DATASET_ITEMS_MAX_BATCH_SIZE
        source_items = [{"id": f"i{i}", "idx": i} for i in range(n - 1, -1, -1)]

        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=source_items,
        )

        result = self._invoke(
            runner,
            ["MyDataset", "--to-project", "B"],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        dest_dataset.insert.assert_called_once()
        inserted = dest_dataset.insert.call_args.args[0]
        # All n items handed to insert() in one call — internal SDK batching
        # then groups them under one batch_group_id => one target version.
        assert len(inserted) == n
        # And the order is reversed (oldest-first) to match UI display order.
        assert [item["idx"] for item in inserted] == list(range(n))

    def test_unknown_name_actionable_error(self, tmp_path: Path) -> None:
        runner = CliRunner()
        client, _, _ = _build_fake_client(
            source_rows=[],
            destination_rows=[],
            items=[],
        )

        result = self._invoke(
            runner,
            ["Missing", "--to-project", "B"],
            client,
            tmp_path,
        )

        assert result.exit_code == 1
        assert "Missing" in result.output
        assert "not found" in result.output.lower()
