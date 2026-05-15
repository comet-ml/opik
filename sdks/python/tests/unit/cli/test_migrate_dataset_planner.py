"""Planner + CLI-help tests for ``opik migrate dataset``.

The planner cases (conflict, ambiguity, project-not-found, default flow
ordering) live here; the meaty version-replay tests live in
``test_migrate_dataset_version_replay.py`` and the cascade tests live in
``test_migrate_dataset_experiments_cascade.py``.

Shared helpers (``_DatasetRow``, ``_Page``, ``_planner_rest_client``)
come from ``_migrate_helpers``.
"""

from __future__ import annotations

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
    _planner_client,
    _planner_rest_client,
)


# ---------------------------------------------------------------------------
# Elapsed-time formatter
# ---------------------------------------------------------------------------


class TestFormatElapsed:
    """Pin the wall-clock duration renderer used in the migrate success /
    failure lines. Sub-minute → one decimal of seconds; past a minute →
    integer ``Mm Ss`` or ``Hh Mm Ss`` (no fractional seconds).
    """

    def test_sub_minute__one_decimal_seconds(self) -> None:
        from opik.cli.migrate.main import _format_elapsed

        assert _format_elapsed(0.0) == "0.0s"
        assert _format_elapsed(12.34) == "12.3s"
        assert _format_elapsed(59.99) == "60.0s"

    def test_minute_range__integer_m_s(self) -> None:
        from opik.cli.migrate.main import _format_elapsed

        assert _format_elapsed(60.0) == "1m 0s"
        assert _format_elapsed(125.7) == "2m 5s"

    def test_hour_range__integer_h_m_s(self) -> None:
        from opik.cli.migrate.main import _format_elapsed

        assert _format_elapsed(3600.0) == "1h 0m 0s"
        assert _format_elapsed(3725.0) == "1h 2m 5s"


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
        assert "--dry-run" in result.output


# ---------------------------------------------------------------------------
# Planner unit tests (no Click invocation)
# ---------------------------------------------------------------------------


class TestPlanBuilding:
    def test_build_dataset_plan__default_flow__orders_rename_create_replay_cascade(
        self,
    ) -> None:
        # The plan emits: rename source -> create destination -> replay
        # versions -> cascade experiments. Each action depends on the
        # previous one having completed (ReplayVersions populates the
        # version_remap that CascadeExperiments reads).
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset", description="d")]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
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

    def test_build_dataset_plan__test_suite__type_forwarded_to_destination(
        self,
    ) -> None:
        # Test suites flow through the same plan shape as plain datasets;
        # the only difference is ``CreateDestination.type`` being forwarded
        # so the target accepts suite-level evaluators + execution_policy
        # via ``ReplayVersions``.
        rest_client = _planner_rest_client(
            [
                _Page(
                    [_DatasetRow(id="src-1", name="MySuite", type="evaluation_suite")]
                ),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
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

    def test_build_dataset_plan__post_rename_name_collides_workspace_wide__raises_conflict(
        self,
    ) -> None:
        # Source rename frees the target name, but the post-rename name
        # "<source>_v1" itself collides with another dataset in the
        # workspace.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([_DatasetRow(id="other-1", name="MyDataset_v1")]),
            ]
        )

        with pytest.raises(ConflictError) as exc_info:
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
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
            client=_planner_client(rest_client),
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
                client=_planner_client(rest_client),
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
                client=_planner_client(rest_client),
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
                client=_planner_client(rest_client),
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
                client=_planner_client(rest_client),
                name="MyDataset",
                to_project="Beta",
                from_project=None,
            )
        message = str(exc_info.value)
        assert "Beta" in message
        # difflib should surface the close one-letter neighbours.
        assert "Did you mean" in message
        assert "Beat" in message or "Best" in message
