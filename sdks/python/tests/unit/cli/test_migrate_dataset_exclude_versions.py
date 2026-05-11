"""Tests for ``opik migrate`` Slice 1 — current-items-only migration.

Slice 1 (``--exclude-versions``) is the fallback path: rename source,
create destination, copy current items in a single insert (one target
version). Slice 2's full version-history replay is the default and lives
in ``test_migrate_dataset_version_replay.py``.

Shared helpers (``_DatasetRow``, ``_Page``, ``_build_fake_client``,
``_planner_rest_client``) come from ``_migrate_helpers``.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import List
from unittest.mock import MagicMock, patch

import pytest
from click.testing import CliRunner

from opik.cli import cli
from opik.cli.migrate.datasets import planner as planner_module
from opik.cli.migrate.errors import (
    AmbiguityError,
    ConflictError,
    DatasetNotFoundError,
    ProjectNotFoundError,
)

from ._migrate_helpers import (
    _DatasetRow,
    _Page,
    _build_fake_client,
    _planner_rest_client,
)


# ---------------------------------------------------------------------------
# Help text
# ---------------------------------------------------------------------------


class TestMigrateHelp:
    def test_migrate_group__help_invoked__lists_subcommands(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "--help"])
        assert result.exit_code == 0
        assert "Migrate Opik entities" in result.output
        assert "dataset" in result.output

    def test_migrate_dataset__help_invoked__lists_required_flags(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "dataset", "--help"])
        assert result.exit_code == 0
        assert "--to-project" in result.output
        assert "--from-project" in result.output
        assert "--exclude-versions" in result.output
        assert "--exclude-experiments" in result.output
        assert "--dry-run" in result.output


# ---------------------------------------------------------------------------
# Planner unit tests (no Click invocation)
# ---------------------------------------------------------------------------


class TestPlanBuilding:
    def test_build_dataset_plan__default_flow__orders_rename_then_create_then_replay_then_cascade(
        self,
    ) -> None:
        # Slice 3 default: full version-history replay followed by the
        # experiment+trace+span cascade. The two replace the slice-1
        # current-items-only copy.
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
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "RenameSource",
            "CreateDestination",
            "ReplayVersions",
            "CascadeExperiments",
        ]
        rename = plan.actions[0]
        assert rename.from_name == "MyDataset"
        assert rename.to_name == "MyDataset_v1"
        assert plan.target_name == "MyDataset"

    def test_build_dataset_plan__exclude_experiments__omits_cascade(
        self,
    ) -> None:
        # Opt out of the experiment cascade. Default replay path otherwise.
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
            exclude_experiments=True,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == ["RenameSource", "CreateDestination", "ReplayVersions"]

    def test_build_dataset_plan__exclude_versions__falls_back_to_copy_current_items(
        self,
    ) -> None:
        # Opt-in to Slice 1 behaviour for users who explicitly don't want
        # version-history replay. ``exclude_versions`` requires
        # ``exclude_experiments`` to avoid producing a remap-less plan that
        # the cascade would silently turn into nothing-migrated.
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
            exclude_versions=True,
            exclude_experiments=True,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == ["RenameSource", "CreateDestination", "CopyCurrentItems"]

    def test_build_dataset_plan__exclude_versions_without_exclude_experiments__raises(
        self,
    ) -> None:
        # The combination is rejected: experiments reference specific
        # version IDs, so skipping replay leaves no remap for the cascade.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
            ]
        )

        with pytest.raises(ValueError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="MyDataset",
                to_project="B",
                from_project=None,
                exclude_versions=True,
            )
        assert "exclude-experiments" in str(exc_info.value)

    def test_build_dataset_plan__test_suite_with_replay__skips_copy_test_suite_config(
        self,
    ) -> None:
        # Test suites with replay on: ``CopyTestSuiteConfig`` is intentionally
        # NOT in the plan because ``ReplayVersions`` walks every source
        # version and forwards each version's suite-level evaluators+policy
        # natively. Adding ``CopyTestSuiteConfig`` would create a leading
        # extra version on the target that the source never had.
        rest_client = _planner_rest_client(
            [
                _Page(
                    [_DatasetRow(id="src-1", name="MySuite", type="evaluation_suite")]
                ),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MySuite",
            to_project="B",
            from_project=None,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "RenameSource",
            "CreateDestination",
            "ReplayVersions",
            "CascadeExperiments",
        ]
        replay = plan.actions[2]
        assert replay.is_test_suite is True

    def test_build_dataset_plan__test_suite_with_exclude_versions__keeps_copy_test_suite_config(
        self,
    ) -> None:
        # Test suites under the slice 1 fallback (``--exclude-versions``):
        # ``CopyTestSuiteConfig`` IS in the plan because the
        # ``CopyCurrentItems`` path doesn't carry suite-level config.
        # Cascade is excluded (slice 1 doesn't populate version_remap).
        rest_client = _planner_rest_client(
            [
                _Page(
                    [_DatasetRow(id="src-1", name="MySuite", type="evaluation_suite")]
                ),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MySuite",
            to_project="B",
            from_project=None,
            exclude_versions=True,
            exclude_experiments=True,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "RenameSource",
            "CreateDestination",
            "CopyTestSuiteConfig",
            "CopyCurrentItems",
        ]

    def test_build_dataset_plan__post_rename_name_collides_workspace_wide__raises_conflict(
        self,
    ) -> None:
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
            )
        assert "MyDataset_v1" in str(exc_info.value)

    def test_build_dataset_plan__post_rename_match_is_source_itself__no_conflict(
        self,
    ) -> None:
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
        )
        assert plan.target_name == "MyDataset"

    def test_build_dataset_plan__source_name_not_found__raises_dataset_not_found(
        self,
    ) -> None:
        rest_client = _planner_rest_client([_Page([])])

        with pytest.raises(DatasetNotFoundError) as exc_info:
            planner_module.build_dataset_plan(
                rest_client=rest_client,
                name="Missing",
                to_project="B",
                from_project=None,
            )
        assert "Missing" in str(exc_info.value)

    def test_build_dataset_plan__source_name_resolves_to_many__raises_ambiguity(
        self,
    ) -> None:
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
            )

    def test_build_dataset_plan__destination_project_missing__raises_project_not_found(
        self,
    ) -> None:
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
            )
        assert "DoesNotExist" in str(exc_info.value)

    def test_build_dataset_plan__destination_project_missing__suggests_similar_names(
        self,
    ) -> None:
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

    def test_migrate_dataset__happyflow(self, tmp_path: Path) -> None:
        # ``--exclude-versions`` runs the Slice 1 current-items-only path
        # exercised here; the Slice 2 replay path is covered by the
        # ``TestVersionReplay`` E2E tests below.
        runner = CliRunner()
        client, source_dataset, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset", description="d")],
            destination_rows=[],
            items=[{"id": "i1", "input": "hello"}, {"id": "i2", "input": "world"}],
        )

        result = self._invoke(
            runner,
            [
                "MyDataset",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        # Rename via REST update — re-passes description/visibility/tags so
        # the BE doesn't wipe them.
        client.rest_client.datasets.update_dataset.assert_called_once_with(
            id="src-1",
            name="MyDataset_v1",
            description="d",
            visibility="private",
            tags=None,
        )
        # Destination created at REST layer with full metadata forwarded.
        client.rest_client.datasets.create_dataset.assert_called_once()
        # Items copied in one dataclass-form call → one target version.
        dest_dataset.__internal_api__insert_items_as_dataclasses__.assert_called_once()
        inserted = (
            dest_dataset.__internal_api__insert_items_as_dataclasses__.call_args.args[0]
        )
        # Each rebuilt item is a fresh DatasetItem with a new server-fresh id.
        assert all(item.id != raw_id for item, raw_id in zip(inserted, ("i2", "i1")))
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

    def test_migrate_dataset__post_rename_collision__exits_with_no_side_effects(
        self, tmp_path: Path
    ) -> None:
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
        client.rest_client.datasets.create_dataset.assert_not_called()
        dest_dataset.__internal_api__insert_items_as_dataclasses__.assert_not_called()
        client.delete_dataset.assert_not_called()

        audit = json.loads((tmp_path / "audit.json").read_text())
        assert audit["status"] == "failed"

    def test_migrate_dataset__dry_run__writes_no_data(self, tmp_path: Path) -> None:
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
        client.rest_client.datasets.create_dataset.assert_not_called()
        dest_dataset.__internal_api__insert_items_as_dataclasses__.assert_not_called()
        client.delete_dataset.assert_not_called()

        audit = json.loads((tmp_path / "audit.json").read_text())
        assert audit["status"] == "planned"
        assert all(a["status"] == "planned" for a in audit["actions"])

    def test_migrate_dataset__items_streamed_newest_first__inserted_oldest_first(
        self, tmp_path: Path
    ) -> None:
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
            [
                "MyDataset",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        inserted = (
            dest_dataset.__internal_api__insert_items_as_dataclasses__.call_args.args[0]
        )
        # Source returned [5,4,3,2,1]; insert receives [1,2,3,4,5].
        assert [item.get_content()["idx"] for item in inserted] == [1, 2, 3, 4, 5]

    def test_migrate_dataset__many_items__one_insert_call_so_one_target_version(
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
            [
                "MyDataset",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        dest_dataset.__internal_api__insert_items_as_dataclasses__.assert_called_once()
        inserted = (
            dest_dataset.__internal_api__insert_items_as_dataclasses__.call_args.args[0]
        )
        # All n items handed to insert() in one call — internal SDK batching
        # then groups them under one batch_group_id => one target version.
        assert len(inserted) == n
        # And the order is reversed (oldest-first) to match UI display order.
        assert [item.get_content()["idx"] for item in inserted] == list(range(n))

    def test_migrate_dataset__per_item_description_and_source_set__preserved_in_target(
        self, tmp_path: Path
    ) -> None:
        # Per-item description / source must round-trip through the copy.
        # (trace_id/span_id/evaluators/execution_policy follow the same code
        # path; description + source are sufficient to pin the contract.)
        runner = CliRunner()
        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[
                {
                    "input": "hello",
                    "description": "per-item description",
                    "source": "manual",
                },
                {
                    "input": "world",
                    "description": "another description",
                    "source": "sdk",
                },
            ],
        )

        result = self._invoke(
            runner,
            [
                "MyDataset",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        inserted = (
            dest_dataset.__internal_api__insert_items_as_dataclasses__.call_args.args[0]
        )
        # Reversed: source returned [hello, world]; written [world, hello].
        assert [it.description for it in inserted] == [
            "another description",
            "per-item description",
        ]
        assert [it.source for it in inserted] == ["sdk", "manual"]

    def test_migrate_dataset__source_has_metadata__forwarded_to_target(
        self, tmp_path: Path
    ) -> None:
        runner = CliRunner()
        client, _, _ = _build_fake_client(
            source_rows=[
                _DatasetRow(
                    id="src-1",
                    name="MyDataset",
                    description="dataset-level description",
                    visibility="private",
                    tags=["a", "b"],
                )
            ],
            destination_rows=[],
            items=[],
        )

        result = self._invoke(
            runner,
            [
                "MyDataset",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        # Both rename PUT and create POST carry full metadata.
        rename_kwargs = client.rest_client.datasets.update_dataset.call_args.kwargs
        assert rename_kwargs["description"] == "dataset-level description"
        assert rename_kwargs["visibility"] == "private"
        assert rename_kwargs["tags"] == ["a", "b"]

        create_kwargs = client.rest_client.datasets.create_dataset.call_args.kwargs
        assert create_kwargs["description"] == "dataset-level description"
        assert create_kwargs["visibility"] == "private"
        assert create_kwargs["tags"] == ["a", "b"]

    def test_migrate_dataset__source_is_test_suite__type_forwarded_and_suite_config_copied(
        self, tmp_path: Path
    ) -> None:
        # Test suites: target is created with type='evaluation_suite' and the
        # latest version's suite-level evaluators+execution_policy are copied.
        runner = CliRunner()
        client, _, _ = _build_fake_client(
            source_rows=[
                _DatasetRow(id="src-1", name="MySuite", type="evaluation_suite")
            ],
            destination_rows=[],
            items=[],
        )

        # Stub list_dataset_versions: latest version carries one evaluator
        # and an execution_policy. The executor should map both into an
        # apply_dataset_item_changes payload against the target id.
        evaluator = MagicMock()
        evaluator.name = "judge-1"
        evaluator.type = "llm_judge"
        evaluator.config = {"model": "gpt-4"}
        exec_policy = MagicMock()
        exec_policy.runs_per_item = 3
        exec_policy.pass_threshold = 2
        latest_version = MagicMock()
        latest_version.evaluators = [evaluator]
        latest_version.execution_policy = exec_policy
        client.rest_client.datasets.list_dataset_versions.return_value = _Page(
            [latest_version]
        )
        target_after_create = MagicMock()
        target_after_create.id = "target-dataset-id"
        client.rest_client.datasets.get_dataset_by_identifier.return_value = (
            target_after_create
        )

        result = self._invoke(
            runner,
            [
                "MySuite",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output

        # Target was created with type forwarded.
        create_kwargs = client.rest_client.datasets.create_dataset.call_args.kwargs
        assert create_kwargs["type"] == "evaluation_suite"

        # Suite config was applied with evaluators + execution_policy.
        apply_kwargs = (
            client.rest_client.datasets.apply_dataset_item_changes.call_args.kwargs
        )
        assert apply_kwargs["id"] == "target-dataset-id"
        assert apply_kwargs["override"] is True
        request = apply_kwargs["request"]
        assert request["execution_policy"] == {
            "runs_per_item": 3,
            "pass_threshold": 2,
        }
        assert request["evaluators"] == [
            {"name": "judge-1", "type": "llm_judge", "config": {"model": "gpt-4"}}
        ]

    def test_migrate_dataset__per_item_evaluator_and_policy_overrides__round_trip(
        self, tmp_path: Path
    ) -> None:
        # An item can override the suite-level evaluators / execution_policy.
        # Those per-item overrides MUST survive the dataclass-form copy.
        from opik.api_objects.dataset.dataset_item import (
            DatasetItem,
            EvaluatorItem,
            ExecutionPolicyItem,
        )

        runner = CliRunner()
        # We need a custom item: build it explicitly and inject through the
        # mock's stream side-effect.
        custom_item = DatasetItem(
            input="custom-q",
            expected="custom-a",
            source="manual",
            evaluators=[
                EvaluatorItem(
                    name="item-specific-judge",
                    type="llm_judge",
                    config={"model": "haiku"},
                ),
            ],
            execution_policy=ExecutionPolicyItem(runs_per_item=10, pass_threshold=7),
        )

        client, source_dataset, dest_dataset = _build_fake_client(
            source_rows=[
                _DatasetRow(id="src-1", name="MySuite", type="evaluation_suite")
            ],
            destination_rows=[],
            items=[],  # we override the stream below
        )
        # Replace the stream with our custom-overridden item.
        source_dataset.__internal_api__stream_items_as_dataclasses__.return_value = (
            iter([custom_item])
        )
        # Stub list_dataset_versions for the suite-config copy step (no
        # config here, so it's effectively a no-op for the CopyTestSuiteConfig
        # action).
        empty_version = MagicMock()
        empty_version.evaluators = None
        empty_version.execution_policy = None
        client.rest_client.datasets.list_dataset_versions.return_value = _Page(
            [empty_version]
        )
        target_after_create = MagicMock()
        target_after_create.id = "target-dataset-id"
        client.rest_client.datasets.get_dataset_by_identifier.return_value = (
            target_after_create
        )

        result = self._invoke(
            runner,
            [
                "MySuite",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        inserted = (
            dest_dataset.__internal_api__insert_items_as_dataclasses__.call_args.args[0]
        )
        assert len(inserted) == 1
        rebuilt = inserted[0]
        # Per-item evaluator override survived.
        assert rebuilt.evaluators is not None
        assert len(rebuilt.evaluators) == 1
        assert rebuilt.evaluators[0].name == "item-specific-judge"
        assert rebuilt.evaluators[0].config == {"model": "haiku"}
        # Per-item execution policy override survived.
        assert rebuilt.execution_policy is not None
        assert rebuilt.execution_policy.runs_per_item == 10
        assert rebuilt.execution_policy.pass_threshold == 7

    def test_migrate_dataset__unknown_dataset_type__raises_unsupported(
        self, tmp_path: Path
    ) -> None:
        runner = CliRunner()
        client, _, dest_dataset = _build_fake_client(
            source_rows=[
                _DatasetRow(id="src-1", name="WeirdDS", type="some_future_type")
            ],
            destination_rows=[],
            items=[],
        )

        result = self._invoke(
            runner,
            ["WeirdDS", "--to-project", "B"],
            client,
            tmp_path,
        )

        assert result.exit_code == 1
        assert "unsupported type" in result.output.lower()
        # No side effects.
        client.rest_client.datasets.update_dataset.assert_not_called()
        client.rest_client.datasets.create_dataset.assert_not_called()
        dest_dataset.__internal_api__insert_items_as_dataclasses__.assert_not_called()

    def test_migrate_dataset__source_has_no_project__migrates_workspace_scoped_source(
        self, tmp_path: Path
    ) -> None:
        # V1 datasets and anything left at workspace scope after auto-migration
        # have project_id=None on the BE. The CLI must handle that source the
        # same way as a project-scoped one — resolver returns project_name=None,
        # rename / create / copy / delete all proceed without a from-project
        # context.
        runner = CliRunner()
        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="LegacyDS", project_id=None)],
            destination_rows=[],
            items=[{"input": "q1"}, {"input": "q2"}],
        )

        result = self._invoke(
            runner,
            [
                "LegacyDS",
                "--to-project",
                "B",
                "--exclude-versions",
                "--exclude-experiments",
            ],
            client,
            tmp_path,
        )

        assert result.exit_code == 0, result.output
        # Source rename happened against the row id; no project context needed.
        client.rest_client.datasets.update_dataset.assert_called_once()
        rename_kwargs = client.rest_client.datasets.update_dataset.call_args.kwargs
        assert rename_kwargs["id"] == "src-1"
        assert rename_kwargs["name"] == "LegacyDS_v1"
        # Destination is created under the explicit --to-project=B regardless
        # of the source's (missing) project.
        create_kwargs = client.rest_client.datasets.create_dataset.call_args.kwargs
        assert create_kwargs["name"] == "LegacyDS"
        assert create_kwargs["project_name"] == "B"
        # Items copied exactly once (one-target-version contract still holds).
        dest_dataset.__internal_api__insert_items_as_dataclasses__.assert_called_once()

        # Source-side reads went through `Opik.get_dataset` with project_name=None
        # — the workspace-scoped lookup path. Pinning this so a refactor that
        # silently routes the source read through a default-project fallback
        # (or breaks against a project_id=None source) trips this test first.
        get_calls = client.get_dataset.call_args_list
        # First get_dataset call is for the (renamed) source.
        first_call_kwargs = get_calls[0].kwargs
        assert first_call_kwargs.get("project_name") is None

    def test_migrate_dataset__source_name_not_found__exits_with_actionable_error(
        self, tmp_path: Path
    ) -> None:
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
