"""Executor tests for ``opik migrate prompt``.

Drives ``execute_plan`` against fully-mocked rest_client surfaces and
asserts that each action dispatches to the right Fern call with the
right kwargs. The verbatim-commit replay path and per-version audit
shape are exercised here (the dataset-side analogue lives in
``test_migrate_dataset_version_replay.py``).
"""

from __future__ import annotations

from typing import Any, List
from unittest.mock import MagicMock

from opik.cli.migrate.audit import AuditLog
from opik.cli.migrate.prompts import executor as executor_module
from opik.cli.migrate.prompts.planner import (
    CreateDestination,
    MigrationPlan,
    RenameSource,
    ReplayVersions,
)
from opik.cli.migrate.prompts.resolver import ResolvedPrompt
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail

from ._migrate_prompt_helpers import _Page, _PromptVersionRow


def _client_with_rest_mock(version_pages: List[_Page]) -> Any:
    rest_client = MagicMock()
    rest_client.prompts.update_prompt = MagicMock()
    rest_client.prompts.create_prompt = MagicMock()
    rest_client.prompts.create_prompt_version = MagicMock(
        side_effect=lambda **kw: MagicMock(
            id=f"dest-version-{kw['version'].commit}",
            commit=kw["version"].commit,
            environments=kw["version"].environments,
        )
    )
    rest_client.prompts.get_prompt_versions.side_effect = version_pages

    client = MagicMock()
    client.rest_client = rest_client
    return client, rest_client


def _make_plan() -> MigrationPlan:
    source = ResolvedPrompt(
        id="src-1",
        name="MyPrompt",
        project_id=None,
        project_name=None,
        description="orig",
        tags=["a"],
        template_structure="text",
    )
    plan = MigrationPlan(source=source, target_name="MyPrompt", to_project="B")
    plan.actions = [
        RenameSource(
            source_id="src-1",
            from_name="MyPrompt",
            to_name="MyPrompt_v1",
            description="orig",
            tags=["a"],
        ),
        CreateDestination(
            name="MyPrompt",
            project_name="B",
            description="orig",
            tags=["a"],
            template_structure="text",
        ),
        ReplayVersions(
            source_prompt_id="src-1",
            source_name_after_rename="MyPrompt_v1",
            source_project_name=None,
            dest_name="MyPrompt",
            dest_project_name="B",
            template_structure="text",
        ),
    ]
    return plan


class TestExecuteActions:
    def test_rename_source__re_passes_description_and_tags(self) -> None:
        # The BE rename PUT wipes description (no COALESCE in the UPDATE);
        # the executor must re-pass it so the source description survives.
        client, rest_client = _client_with_rest_mock(
            [_Page([])],  # no versions
        )
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        rest_client.prompts.update_prompt.assert_called_once_with(
            id="src-1",
            name="MyPrompt_v1",
            description="orig",
            tags=["a"],
        )

    def test_create_destination__omits_template_so_be_does_not_auto_mint_v1(
        self,
    ) -> None:
        # If we passed a template here, the BE would auto-create a v1
        # with its own commit hash, defeating the verbatim-replay
        # guarantee. The executor must NOT include template in the
        # create_prompt call.
        client, rest_client = _client_with_rest_mock([_Page([])])
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        rest_client.prompts.create_prompt.assert_called_once()
        kwargs = rest_client.prompts.create_prompt.call_args.kwargs
        assert kwargs["name"] == "MyPrompt"
        assert kwargs["project_name"] == "B"
        assert kwargs["description"] == "orig"
        assert kwargs["tags"] == ["a"]
        assert kwargs["template_structure"] == "text"
        # Critical: no template, no type, no metadata at the container
        # level — those are version-level fields handled by the replay.
        assert "template" not in kwargs
        assert "type" not in kwargs
        assert "metadata" not in kwargs

    def test_replay_versions__preserves_commit_hashes_verbatim(self) -> None:
        # The verbatim-commit invariant is the architectural reason this
        # slice exists: source versions are minted on the destination
        # with the source's exact commit hash, made possible by the
        # destination prompt's fresh id (so (workspace_id, prompt_id,
        # commit) never collides).
        v1 = _PromptVersionRow(
            id="src-v-1",
            prompt_id="src-1",
            commit="aaaaaaaa",
            template="hello {{name}}",
            type="mustache",
        )
        v2 = _PromptVersionRow(
            id="src-v-2",
            prompt_id="src-1",
            commit="bbbbbbbb",
            template="hello {{name}} v2",
            type="mustache",
        )
        # BE returns newest-first (pv.id DESC); the replay loop reverses
        # to oldest-first. Pass them in newest-first order to mirror the
        # BE response shape.
        client, rest_client = _client_with_rest_mock([_Page([v2, v1])])
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        # Two POST /v1/private/prompts/versions calls, oldest-first.
        assert rest_client.prompts.create_prompt_version.call_count == 2
        first_call = rest_client.prompts.create_prompt_version.call_args_list[0]
        second_call = rest_client.prompts.create_prompt_version.call_args_list[1]

        first_version = first_call.kwargs["version"]
        second_version = second_call.kwargs["version"]
        assert isinstance(first_version, PromptVersionDetail)
        assert isinstance(second_version, PromptVersionDetail)
        assert first_version.commit == "aaaaaaaa"
        assert second_version.commit == "bbbbbbbb"
        assert first_version.template == "hello {{name}}"
        assert second_version.template == "hello {{name}} v2"

    def test_replay_versions__carries_environments_verbatim(self) -> None:
        # Environment ownership is per-version data the BE accepts inline on
        # create_prompt_version. The history mixes an env-less version, a
        # non-latest version owning a single env, and a latest version owning
        # multiple envs — proving single + multi env work and that a
        # non-latest version's env is preserved (not just the newest one's).
        v1 = _PromptVersionRow(
            id="src-v-1",
            prompt_id="src-1",
            commit="aaaaaaaa",
            template="hello",
        )
        v2 = _PromptVersionRow(
            id="src-v-2",
            prompt_id="src-1",
            commit="bbbbbbbb",
            template="hi",
            environments=["development"],
        )
        v3 = _PromptVersionRow(
            id="src-v-3",
            prompt_id="src-1",
            commit="cccccccc",
            template="hey",
            environments=["production", "staging"],
        )
        # BE returns newest-first; the loop reverses to oldest-first.
        client, rest_client = _client_with_rest_mock([_Page([v3, v2, v1])])
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        calls = rest_client.prompts.create_prompt_version.call_args_list
        assert calls[0].kwargs["version"].environments is None
        assert calls[1].kwargs["version"].environments == ["development"]
        assert calls[2].kwargs["version"].environments == ["production", "staging"]

    def test_replay_versions__records_environments_in_audit(self) -> None:
        v1 = _PromptVersionRow(
            id="src-v-1",
            prompt_id="src-1",
            commit="aaaaaaaa",
            template="hello",
            environments=["staging", "production"],
        )
        client, _ = _client_with_rest_mock([_Page([v1])])
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        record = next(a for a in audit.actions if a["type"] == "replay_prompt_version")
        # Environments are recorded verbatim (order is irrelevant), so compare
        # order-insensitively.
        assert sorted(record["source_environments"]) == ["production", "staging"]
        assert sorted(record["target_environments"]) == ["production", "staging"]

    def test_replay_versions__populates_prompt_version_id_remap(self) -> None:
        # Slice 7 (OPIK-6575) reads ``plan.prompt_version_id_remap`` to
        # remap experiment FK references. The executor must populate it.
        v1 = _PromptVersionRow(
            id="src-v-1",
            prompt_id="src-1",
            commit="aaaaaaaa",
            template="hello",
        )
        v2 = _PromptVersionRow(
            id="src-v-2",
            prompt_id="src-1",
            commit="bbbbbbbb",
            template="hi",
        )
        client, rest_client = _client_with_rest_mock([_Page([v2, v1])])
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        assert plan.prompt_version_id_remap == {
            "src-v-1": "dest-version-aaaaaaaa",
            "src-v-2": "dest-version-bbbbbbbb",
        }

    def test_replay_versions__records_one_audit_entry_per_version(self) -> None:
        v1 = _PromptVersionRow(
            id="src-v-1",
            prompt_id="src-1",
            commit="aaaaaaaa",
            template="hello",
        )
        v2 = _PromptVersionRow(
            id="src-v-2",
            prompt_id="src-1",
            commit="bbbbbbbb",
            template="hi",
        )
        client, _ = _client_with_rest_mock([_Page([v2, v1])])
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.execute_plan(client, plan, audit)

        per_version_records = [
            a for a in audit.actions if a["type"] == "replay_prompt_version"
        ]
        assert len(per_version_records) == 2
        # All per-version records are recorded as ``ok`` (the per-version
        # audit happens after a successful create_prompt_version).
        assert all(a["status"] == "ok" for a in per_version_records)
        assert {a["source_commit"] for a in per_version_records} == {
            "aaaaaaaa",
            "bbbbbbbb",
        }


class TestRecordPlanned:
    def test_record_planned__emits_one_planned_entry_per_action(self) -> None:
        # Dry-run path: every action gets a ``planned`` record, no REST
        # calls fire.
        plan = _make_plan()
        audit = AuditLog(command="opik migrate prompt", args={})

        executor_module.record_planned(plan, audit)

        statuses = [a["status"] for a in audit.actions]
        types = [a["type"] for a in audit.actions]
        assert statuses == ["planned", "planned", "planned"]
        assert types == [
            "rename_source",
            "create_destination",
            "replay_versions",
        ]
