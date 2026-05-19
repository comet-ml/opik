"""Planner tests for ``opik migrate prompt``.

The dataset planner tests are next door; this file tracks the same
patterns (happy-path action ordering, conflict detection, project-not-
found, source-not-found) for the prompt slice.
"""

from __future__ import annotations

import pytest
from click.testing import CliRunner

from opik.cli import cli
from opik.cli.migrate.errors import (
    ConflictError,
    ProjectNotFoundError,
    PromptNotFoundError,
)
from opik.cli.migrate.prompts import planner as prompt_planner

from ._migrate_prompt_helpers import (
    _Page,
    _PromptRow,
    _planner_client,
    _planner_rest_client,
)


class TestMigratePromptHelp:
    def test_migrate_group_help__lists_prompt_subcommand(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "--help"])
        assert result.exit_code == 0
        assert "prompt" in result.output

    def test_migrate_prompt_help__lists_required_flags(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "prompt", "--help"])
        assert result.exit_code == 0
        assert "--to-project" in result.output
        assert "--from-project" in result.output
        assert "--dry-run" in result.output


class TestPlanBuilding:
    def test_build_prompt_plan__default_flow__orders_rename_create_replay(
        self,
    ) -> None:
        # Action ordering invariant: the rename frees the workspace-unique
        # name BEFORE the destination claims it, and the replay loop runs
        # AFTER the destination container exists.
        rest_client = _planner_rest_client(
            [
                _Page([_PromptRow(id="src-1", name="MyPrompt", description="d")]),
                _Page([]),  # rename-collision preflight finds nothing
            ]
        )

        plan = prompt_planner.build_prompt_plan(
            client=_planner_client(rest_client),
            name="MyPrompt",
            to_project="B",
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == ["RenameSource", "CreateDestination", "ReplayVersions"]

    def test_build_prompt_plan__rename_re_passes_description_and_tags(
        self,
    ) -> None:
        # The BE rename PUT wipes description (UPDATE has no COALESCE on
        # description); the planner forwards the source's description and
        # tags into the RenameSource record so the executor re-passes them.
        rest_client = _planner_rest_client(
            [
                _Page(
                    [
                        _PromptRow(
                            id="src-1",
                            name="MyPrompt",
                            description="orig description",
                            tags=["a", "b"],
                        )
                    ]
                ),
                _Page([]),
            ]
        )

        plan = prompt_planner.build_prompt_plan(
            client=_planner_client(rest_client),
            name="MyPrompt",
            to_project="B",
        )

        rename = plan.actions[0]
        assert isinstance(rename, prompt_planner.RenameSource)
        assert rename.from_name == "MyPrompt"
        assert rename.to_name == "MyPrompt_v1"
        assert rename.description == "orig description"
        assert rename.tags == ["a", "b"]

    def test_build_prompt_plan__create_destination_carries_metadata(
        self,
    ) -> None:
        # CreateDestination forwards container-level metadata so the
        # destination inherits description / tags / template_structure
        # from the source.
        rest_client = _planner_rest_client(
            [
                _Page(
                    [
                        _PromptRow(
                            id="src-1",
                            name="MyPrompt",
                            description="orig",
                            tags=["a"],
                            template_structure="chat",
                        )
                    ]
                ),
                _Page([]),
            ]
        )

        plan = prompt_planner.build_prompt_plan(
            client=_planner_client(rest_client),
            name="MyPrompt",
            to_project="B",
        )

        create = plan.actions[1]
        assert isinstance(create, prompt_planner.CreateDestination)
        assert create.name == "MyPrompt"
        assert create.project_name == "B"
        assert create.description == "orig"
        assert create.tags == ["a"]
        assert create.template_structure == "chat"

    def test_build_prompt_plan__replay_versions_carries_source_id_and_template_structure(
        self,
    ) -> None:
        rest_client = _planner_rest_client(
            [
                _Page(
                    [
                        _PromptRow(
                            id="src-1",
                            name="MyPrompt",
                            template_structure="chat",
                        )
                    ]
                ),
                _Page([]),
            ]
        )

        plan = prompt_planner.build_prompt_plan(
            client=_planner_client(rest_client),
            name="MyPrompt",
            to_project="B",
        )

        replay = plan.actions[2]
        assert isinstance(replay, prompt_planner.ReplayVersions)
        assert replay.source_prompt_id == "src-1"
        assert replay.source_name_after_rename == "MyPrompt_v1"
        assert replay.dest_name == "MyPrompt"
        assert replay.dest_project_name == "B"
        assert replay.template_structure == "chat"


class TestPreflightErrors:
    def test_build_prompt_plan__destination_project_missing__raises(
        self,
    ) -> None:
        rest_client = _planner_rest_client(
            [],  # no prompt lookups happen if project preflight fails
            target_project_exists=False,
        )

        with pytest.raises(ProjectNotFoundError) as excinfo:
            prompt_planner.build_prompt_plan(
                client=_planner_client(rest_client),
                name="MyPrompt",
                to_project="DoesNotExist",
            )

        assert "DoesNotExist" in str(excinfo.value)

    def test_build_prompt_plan__source_prompt_missing__raises(self) -> None:
        rest_client = _planner_rest_client(
            [_Page([])]  # source lookup returns no matches
        )

        with pytest.raises(PromptNotFoundError) as excinfo:
            prompt_planner.build_prompt_plan(
                client=_planner_client(rest_client),
                name="Missing",
                to_project="B",
            )

        assert "Missing" in str(excinfo.value)
        assert "workspace" in str(excinfo.value).lower()

    def test_build_prompt_plan__from_project_set_and_missing__raises_with_project_scope(
        self,
    ) -> None:
        # When --from-project is passed and the source prompt is not in that
        # project, the not-found error should name the project (not "the
        # workspace") so the user knows where we looked.
        rest_client = _planner_rest_client(
            [_Page([])]  # source lookup returns no matches in the project
        )

        with pytest.raises(PromptNotFoundError) as excinfo:
            prompt_planner.build_prompt_plan(
                client=_planner_client(rest_client),
                name="MyPrompt",
                to_project="B",
                from_project="A",
            )

        assert "MyPrompt" in str(excinfo.value)
        assert "project 'A'" in str(excinfo.value)

    def test_build_prompt_plan__rename_collision__raises(self) -> None:
        # Source resolves, but the rename target ``MyPrompt_v1`` is already
        # taken by another prompt -> ConflictError.
        rest_client = _planner_rest_client(
            [
                _Page([_PromptRow(id="src-1", name="MyPrompt")]),
                _Page([_PromptRow(id="other-1", name="MyPrompt_v1")]),
            ]
        )

        with pytest.raises(ConflictError) as excinfo:
            prompt_planner.build_prompt_plan(
                client=_planner_client(rest_client),
                name="MyPrompt",
                to_project="B",
            )

        assert "MyPrompt_v1" in str(excinfo.value)
