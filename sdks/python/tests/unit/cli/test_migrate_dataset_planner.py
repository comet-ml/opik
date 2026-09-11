"""Planner + CLI-help tests for ``opik migrate dataset``.

The planner cases (conflict, project-not-found, default flow ordering)
live here; the meaty version-replay tests live in
``test_migrate_dataset_version_replay.py`` and the cascade tests live in
``test_migrate_dataset_experiments_cascade.py``.

Shared helpers (``_DatasetRow``, ``_Page``, ``_planner_rest_client``)
come from ``_migrate_helpers``.
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from click.testing import CliRunner

from opik.cli import cli
from opik.cli.migrate.audit import AuditLog
from opik.cli.migrate.checkpoint import MigrationCheckpoint
from opik.cli.migrate.datasets import planner as planner_module
from opik.cli.migrate.datasets.planner import TEMP_MIGRATION_MARKER_TAG
from opik.cli.migrate.datasets.resume import ReconstructedRemaps
from opik.cli.migrate.errors import (
    ConflictError,
    DatasetNotFoundError,
    MigrationError,
    ProjectNotFoundError,
)

from ._migrate_helpers import (
    _build_fake_client,
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
# OPIK-6599: loud-fail on skipped items
#
# When the cascade emits any ``skip`` audit record, the migrate must:
#   1. Finalize the audit log to ``failed`` (not ``ok``)
#   2. Print a SKIP_SUMMARY line to stderr (not stdout) so CI gates can
#      grep without parsing the JSON
#   3. Exit non-zero
#
# Tests below cover ``_finalize_with_skips_or_ok`` directly so they don't
# need a fully-wired Opik client + REST server stub. The CLI is only the
# wrapper around this helper.
# ---------------------------------------------------------------------------


class TestFinalizeWithSkipsOrOk:
    def _make_audit_with_skips(self) -> AuditLog:
        audit = AuditLog(command="opik migrate dataset", args={})
        audit.record(
            type="skip",
            status="skipped",
            details={
                "reason": "items_missing_dataset_item_remap",
                "experiment_id": "src-exp-1",
                "experiment_name": "exp-1",
                "count": 2500,
                "sample_source_ids": ["src-ds-item-1", "src-ds-item-2"],
            },
        )
        return audit

    def test_skips_present__finalizes_failed_exits_1_stderr_summary(
        self, tmp_path, capsys
    ) -> None:
        from opik.cli.migrate.main import _finalize_with_skips_or_ok

        audit = self._make_audit_with_skips()
        audit_path = tmp_path / "audit.json"

        with pytest.raises(SystemExit) as exc:
            _finalize_with_skips_or_ok(
                audit,
                audit_path,
                name="MyDataset",
                target_label="MyDataset",
                target_project="DestProject",
                elapsed_seconds=12.3,
            )

        # AC 1: non-zero exit code
        assert exc.value.code == 1

        captured = capsys.readouterr()
        # AC 3: skip message on stderr (not stdout), with the
        # machine-parseable SKIP_SUMMARY suffix
        assert "SKIP_SUMMARY:" in captured.err
        assert "items_skipped_missing_item=2500" in captured.err
        assert "experiments_skipped=0" in captured.err
        assert "items_skipped_missing_trace=0" in captured.err
        assert "NOT rolled back" in captured.err
        # Rollback hint names the entities the operator must remove,
        # the destination project, and the rename-back step on the source.
        assert "roll back manually" in captured.err
        assert "DestProject" in captured.err
        assert "MyDataset_v1" in captured.err

        # AC 2: audit finalized to failed with skip record intact
        on_disk = json.loads(audit_path.read_text())
        assert on_disk["status"] == "failed"
        assert any(a.get("status") == "skipped" for a in on_disk["actions"])

    def test_no_skips__finalizes_ok_no_exit_no_stderr(self, tmp_path, capsys) -> None:
        from opik.cli.migrate.main import _finalize_with_skips_or_ok

        audit = AuditLog(command="opik migrate dataset", args={})
        audit.record(type="rename_source", status="ok", details={})
        audit_path = tmp_path / "audit.json"

        # Happy path returns without raising; happy-path message goes to
        # stdout, stderr stays clean.
        _finalize_with_skips_or_ok(
            audit,
            audit_path,
            name="MyDataset",
            target_label="MyDataset",
            target_project="DestProject",
            elapsed_seconds=5.0,
        )

        captured = capsys.readouterr()
        assert "SKIP_SUMMARY:" not in captured.err
        on_disk = json.loads(audit_path.read_text())
        assert on_disk["status"] == "ok"

    def test_multiple_skip_records__totals_aggregated_by_reason(
        self, tmp_path, capsys
    ) -> None:
        from opik.cli.migrate.main import _finalize_with_skips_or_ok

        # Two experiments, each contributing skips for both reasons.
        audit = AuditLog(command="opik migrate dataset", args={})
        for exp_id in ("src-exp-1", "src-exp-2"):
            audit.record(
                type="skip",
                status="skipped",
                details={
                    "reason": "items_missing_trace_remap",
                    "experiment_id": exp_id,
                    "experiment_name": exp_id,
                    "count": 7,
                    "sample_source_ids": [],
                },
            )
            audit.record(
                type="skip",
                status="skipped",
                details={
                    "reason": "items_missing_dataset_item_remap",
                    "experiment_id": exp_id,
                    "experiment_name": exp_id,
                    "count": 3,
                    "sample_source_ids": [],
                },
            )

        with pytest.raises(SystemExit):
            _finalize_with_skips_or_ok(
                audit,
                tmp_path / "audit.json",
                name="MyDataset",
                target_label="MyDataset",
                target_project="DestProject",
                elapsed_seconds=1.0,
            )

        captured = capsys.readouterr()
        # 7 + 7 = 14 trace skips, 3 + 3 = 6 dataset-item skips
        assert "items_skipped_missing_trace=14" in captured.err
        assert "items_skipped_missing_item=6" in captured.err


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

    def test_migrate_dataset__help_invoked__lists_exclude_experiments(self) -> None:
        # OPIK-7161 AC: the opt-out flag must be discoverable in --help.
        runner = CliRunner()
        result = runner.invoke(cli, ["migrate", "dataset", "--help"])
        assert result.exit_code == 0
        assert "--exclude-experiments" in result.output

    def test_migrate_dataset__exclude_experiments__cli_run_skips_and_reports(
        self, tmp_path
    ) -> None:
        # OPIK-7161: exercise the flag through the public Click entrypoint,
        # not just the finalize helper, so the option -> build_dataset_plan
        # -> finalize plumbing in migrate_dataset_command is covered end to
        # end (per .agents/skills/python-sdk/testing.md: test the public API).
        # The fake client mocks the whole rename/create/replay surface; with
        # --exclude-experiments the plan carries no cascade actions, so the
        # command reaches the success finalize with zero experiment work.
        client, _, _ = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "item-a", "input": "hello"}],
        )
        audit_path = tmp_path / "audit.json"

        runner = CliRunner()
        with patch("opik.cli.migrate.main._build_client", return_value=client):
            result = runner.invoke(
                cli,
                [
                    "migrate",
                    "dataset",
                    "MyDataset",
                    "--to-project",
                    "B",
                    "--exclude-experiments",
                    "--audit-log",
                    str(audit_path),
                ],
            )

        assert result.exit_code == 0, result.output
        # User-facing output makes the intentional skip clear.
        assert "--exclude-experiments" in result.output
        assert "skipped" in result.output
        # No experiment cascade ran: the source dataset was never queried for
        # experiments (find_experiments belongs only to the cascade path).
        assert client.rest_client.experiments.find_experiments.call_count == 0
        # Audit log finalized ok and recorded the flag in its args.
        on_disk = json.loads(audit_path.read_text())
        assert on_disk["status"] == "ok"
        assert on_disk["args"]["exclude_experiments"] is True
        cascade_types = {a.get("type") for a in on_disk["actions"]}
        assert "cascade_experiments" not in cascade_types
        assert "cascade_optimizations" not in cascade_types


class TestTempDestRenameOnSuccess:
    """OPIK-7162 acceptance criteria, exercised through the public Click
    entrypoint: the source keeps its name until the copy succeeds, then the
    handoff runs (source -> _v1, temp -> original). A mid-run failure leaves
    the source name untouched, and a re-run after failure is safe/idempotent.

    All cases use ``--exclude-experiments`` so the plan is the minimal
    Create -> Replay -> Rename -> Promote shape and the assertions stay
    focused on the handoff, not the cascade.
    """

    def _run(self, client, tmp_path, extra_args=()):
        audit_path = tmp_path / "audit.json"
        runner = CliRunner()
        with patch("opik.cli.migrate.main._build_client", return_value=client):
            result = runner.invoke(
                cli,
                [
                    "migrate",
                    "dataset",
                    "MyDataset",
                    "--to-project",
                    "B",
                    "--exclude-experiments",
                    "--audit-log",
                    str(audit_path),
                    *extra_args,
                ],
            )
        return result, audit_path

    def test_success__source_renamed_to_v1_and_dest_promoted_to_original(
        self, tmp_path
    ) -> None:
        # AC: a successful migration leaves the destination under the
        # original name and the source under the _v1 suffix. The two renames
        # happen via update_dataset PUTs; the destination is created under
        # the temp name first.
        client, _, _ = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "item-a", "input": "hello"}],
        )
        result, audit_path = self._run(client, tmp_path)

        assert result.exit_code == 0, result.output
        rest = client.rest_client
        # Destination created under the temp name (not the final name), and
        # stamped with the migration marker tag so a future re-run can prove
        # it's ours before discarding it.
        create_kwargs = rest.datasets.create_dataset.call_args.kwargs
        assert create_kwargs["name"] == "MyDataset__migrating"
        assert TEMP_MIGRATION_MARKER_TAG in (create_kwargs["tags"] or [])
        # Two rename PUTs: source -> _v1, then temp -> original. The promote PUT
        # re-passes the source's ORIGINAL tags (marker stripped).
        rename_calls = [c.kwargs for c in rest.datasets.update_dataset.call_args_list]
        source_rename = next(c for c in rename_calls if c["id"] == "src-1")
        assert source_rename["name"] == "MyDataset_v1"
        promote = next(c for c in rename_calls if c.get("name") == "MyDataset")
        assert promote["name"] == "MyDataset"
        # The promote PUT must pass tags as an EXPLICIT list (never None), so the
        # BE actually overwrites and drops the marker. A live backend treats
        # ``tags=None`` as "leave unchanged", which would strand the marker on a
        # source with no tags — so assert the concrete list, not just marker
        # absence. Source here has no tags -> promote clears with [].
        assert promote["tags"] == []
        assert TEMP_MIGRATION_MARKER_TAG not in promote["tags"]
        # Audit ends ok and records the handoff actions in order.
        on_disk = json.loads(audit_path.read_text())
        assert on_disk["status"] == "ok"
        ok_types = [a["type"] for a in on_disk["actions"] if a.get("status") == "ok"]
        assert ok_types.index("rename_source") < ok_types.index("promote_destination")
        assert ok_types.index("create_destination") < ok_types.index("rename_source")

    def test_success__source_tags_preserved_marker_stripped(self, tmp_path) -> None:
        # When the source has real tags, the temp create adds the marker
        # alongside them, and the promote re-passes exactly the source's
        # originals (marker dropped, real tags kept).
        client, _, _ = _build_fake_client(
            source_rows=[
                _DatasetRow(id="src-1", name="MyDataset", tags=["team-a", "prod"])
            ],
            destination_rows=[],
            items=[{"id": "item-a", "input": "hello"}],
        )
        result, _ = self._run(client, tmp_path)

        assert result.exit_code == 0, result.output
        rest = client.rest_client
        create_tags = rest.datasets.create_dataset.call_args.kwargs["tags"]
        assert set(create_tags) == {"team-a", "prod", TEMP_MIGRATION_MARKER_TAG}
        promote = next(
            c.kwargs
            for c in rest.datasets.update_dataset.call_args_list
            if c.kwargs.get("name") == "MyDataset"
        )
        assert promote["tags"] == ["team-a", "prod"]
        assert TEMP_MIGRATION_MARKER_TAG not in promote["tags"]

    def test_midrun_failure__source_name_untouched(self, tmp_path) -> None:
        # AC: a migration interrupted mid-run leaves the source name
        # untouched. Blow up the destination create (the first copy action);
        # the source-rename PUT must never fire.
        client, _, _ = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "item-a", "input": "hello"}],
        )
        client.rest_client.datasets.create_dataset.side_effect = RuntimeError(
            "boom mid-copy"
        )
        result, audit_path = self._run(client, tmp_path)

        assert result.exit_code == 1
        # No update_dataset PUT touched the source id -> its name is intact.
        source_touched = [
            c
            for c in client.rest_client.datasets.update_dataset.call_args_list
            if c.kwargs.get("id") == "src-1"
        ]
        assert source_touched == []
        on_disk = json.loads(audit_path.read_text())
        assert on_disk["status"] == "failed"
        # The handoff actions never reached ``ok``.
        ok_types = {a["type"] for a in on_disk["actions"] if a.get("status") == "ok"}
        assert "rename_source" not in ok_types
        assert "promote_destination" not in ok_types

    def test_rerun_after_failure__discards_stale_temp_then_completes(
        self, tmp_path
    ) -> None:
        # AC: re-running after an interrupted run completes with no manual
        # cleanup. A stale ``MyDataset__migrating`` from the prior failed run —
        # carrying the migration marker tag that proves it's ours — is
        # discovered and deleted before the destination is recreated.
        client, _, _ = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[{"id": "item-a", "input": "hello"}],
            stale_temp_rows=[
                _DatasetRow(
                    id="stale-1",
                    name="MyDataset__migrating",
                    tags=[TEMP_MIGRATION_MARKER_TAG],
                )
            ],
        )
        result, audit_path = self._run(client, tmp_path)

        assert result.exit_code == 0, result.output
        # The stale temp was deleted by id before recreate.
        client.rest_client.datasets.delete_dataset.assert_called_once_with(id="stale-1")
        on_disk = json.loads(audit_path.read_text())
        assert on_disk["status"] == "ok"
        action_types = [
            a["type"] for a in on_disk["actions"] if a.get("status") == "ok"
        ]
        assert action_types.index("discard_stale_temp") < action_types.index(
            "create_destination"
        )


# ---------------------------------------------------------------------------
# Planner unit tests (no Click invocation)
# ---------------------------------------------------------------------------


class TestPlanBuilding:
    def test_build_dataset_plan__default_flow__orders_create_replay_cascades_then_handoff(
        self,
    ) -> None:
        # OPIK-7162: the plan builds the destination under a temp name FIRST
        # (source keeps its name), runs the copy + cascades, then does the
        # name handoff LAST: rename source -> <name>_v1, promote temp ->
        # <name>. The order is load-bearing on two axes:
        #   * CascadeOptimizations before CascadeExperiments (opt-id remap).
        #   * RenameSource before PromoteDestination (source-away then
        #     destination-in, so <name> is never held by two rows at once).
        # Three find_datasets pages: source resolve, _v1 collision check,
        # __migrating stale-temp lookup.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset", description="d")]),
                _Page([]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="B",
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "CreateDestination",
            "ReplayVersions",
            "CascadeOptimizations",
            "CascadeExperiments",
            "RenameSource",
            "PromoteDestination",
        ]
        # Destination is created under the temp name, not the final name.
        create = plan.actions[0]
        assert create.name == "MyDataset__migrating"
        replay = plan.actions[1]
        assert replay.source_name == "MyDataset"
        assert replay.dest_name == "MyDataset__migrating"
        # Handoff: source away first, destination in second.
        rename = plan.actions[4]
        assert rename.from_name == "MyDataset"
        assert rename.to_name == "MyDataset_v1"
        promote = plan.actions[5]
        assert promote.from_name == "MyDataset__migrating"
        assert promote.to_name == "MyDataset"
        assert plan.target_name == "MyDataset"
        # New remap dict starts empty; _cascade_optimizations populates it.
        assert plan.optimization_id_remap == {}

    def test_build_dataset_plan__exclude_experiments__omits_both_cascades(
        self,
    ) -> None:
        # OPIK-7161: --exclude-experiments drops the experiment stage AND
        # the optimization stage (optimizations are containers for the
        # skipped experiments). No cascade actions, but the name handoff
        # (rename + promote) still runs after the dataset + versions copy.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset", description="d")]),
                _Page([]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="B",
            exclude_experiments=True,
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "CreateDestination",
            "ReplayVersions",
            "RenameSource",
            "PromoteDestination",
        ]
        assert not any(
            isinstance(a, planner_module.CascadeExperiments) for a in plan.actions
        )
        assert not any(
            isinstance(a, planner_module.CascadeOptimizations) for a in plan.actions
        )

    def test_build_dataset_plan__exclude_experiments_default_false__keeps_cascades(
        self,
    ) -> None:
        # Default (flag off) is unchanged: both cascades still emitted.
        # Guards the opt-out default so a plain migrate never silently
        # starts skipping experiments.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="B",
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "CreateDestination",
            "ReplayVersions",
            "CascadeOptimizations",
            "CascadeExperiments",
            "RenameSource",
            "PromoteDestination",
        ]

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
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MySuite",
            to_project="B",
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "CreateDestination",
            "ReplayVersions",
            "CascadeOptimizations",
            "CascadeExperiments",
            "RenameSource",
            "PromoteDestination",
        ]
        replay = plan.actions[1]
        assert replay.is_test_suite is True

    def test_build_dataset_plan__rename_target_collides_workspace_wide__raises_conflict(
        self,
    ) -> None:
        # The eventual source-rename target "<source>_v1" collides with
        # another dataset in the workspace — caught up-front so a doomed run
        # never does any copy work.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([_DatasetRow(id="other-1", name="MyDataset_v1")]),
                _Page([]),
            ]
        )

        with pytest.raises(ConflictError) as exc_info:
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
                name="MyDataset",
                to_project="B",
            )
        assert "MyDataset_v1" in str(exc_info.value)

    def test_build_dataset_plan__rename_target_match_is_source_itself__no_conflict(
        self,
    ) -> None:
        # When find_datasets returns the source itself for the _v1 check, we
        # must not treat that as a collision — it's about to be renamed.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([_DatasetRow(id="src-1", name="MyDataset_v1")]),
                _Page([]),
            ]
        )
        # Should NOT raise: the only "match" is the source dataset itself.
        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="B",
        )
        assert plan.target_name == "MyDataset"

    def test_build_dataset_plan__marked_stale_temp__prepends_discard_action(
        self,
    ) -> None:
        # OPIK-7162 safe re-run: a leftover "<name>__migrating" carrying the
        # migration marker tag (proof it's ours) is detected and a
        # DiscardStaleTemp action is prepended so the re-run starts clean.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
                _Page(
                    [
                        _DatasetRow(
                            id="stale-1",
                            name="MyDataset__migrating",
                            tags=[TEMP_MIGRATION_MARKER_TAG],
                        )
                    ]
                ),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="B",
        )

        types = [type(a).__name__ for a in plan.actions]
        assert types == [
            "DiscardStaleTemp",
            "CreateDestination",
            "ReplayVersions",
            "CascadeOptimizations",
            "CascadeExperiments",
            "RenameSource",
            "PromoteDestination",
        ]
        discard = plan.actions[0]
        assert discard.temp_id == "stale-1"
        assert discard.temp_name == "MyDataset__migrating"

    def test_build_dataset_plan__unmarked_name_collision__raises_conflict(
        self,
    ) -> None:
        # A dataset named "<name>__migrating" WITHOUT the migration marker is a
        # real user dataset that merely shares the name — it must NOT be
        # deleted. The planner aborts with ConflictError instead.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
                _Page([_DatasetRow(id="user-1", name="MyDataset__migrating")]),
            ]
        )

        with pytest.raises(ConflictError) as exc_info:
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
                name="MyDataset",
                to_project="B",
            )
        assert "MyDataset__migrating" in str(exc_info.value)
        assert TEMP_MIGRATION_MARKER_TAG in str(exc_info.value)

    def test_build_dataset_plan__same_from_and_to_project_flag__raises_conflict(
        self,
    ) -> None:
        # Cheap early-out: user literally passed --from-project A --to-project A.
        # Rejected before any lookup.
        rest_client = _planner_rest_client(
            [_Page([_DatasetRow(id="src-1", name="MyDataset")])]
        )

        with pytest.raises(ConflictError, match="same project"):
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
                name="MyDataset",
                to_project="A",
                from_project="A",
            )

    def test_build_dataset_plan__omitted_flag_source_in_dest_project__raises_conflict(
        self,
    ) -> None:
        # The gap the flag-only check missed: --from-project is OMITTED, but the
        # source actually lives in the destination project. resolve_source
        # populates source.project_name from the row's project_id, so the
        # authoritative post-resolve guard still catches it.
        source_row = _DatasetRow(id="src-1", name="MyDataset", project_id="proj-A")
        rest_client = _planner_rest_client([_Page([source_row])])
        # project_name_for_row -> client.get_project(id="proj-A").name == "A".
        proj = MagicMock()
        proj.name = "A"
        rest_client.projects.get_project_by_id.return_value = proj

        with pytest.raises(ConflictError, match="same project"):
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
                name="MyDataset",
                to_project="A",
            )

    def test_build_dataset_plan__workspace_scoped_source__no_same_project_abort(
        self,
    ) -> None:
        # A workspace-scoped source (no project_id -> project_name is None) has
        # no single project to collide with --to-project, so a workspace-scoped
        # -> project migrate is legitimate and must NOT be blocked.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset", project_id=None)]),
                _Page([]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="A",
        )
        assert plan.target_name == "MyDataset"

    def test_build_dataset_plan__no_stale_temp__no_discard_action(self) -> None:
        # The common case: no leftover temp, so no DiscardStaleTemp emitted.
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            client=_planner_client(rest_client),
            name="MyDataset",
            to_project="B",
        )

        assert not any(
            isinstance(a, planner_module.DiscardStaleTemp) for a in plan.actions
        )

    def test_build_dataset_plan__source_name_not_found__raises_dataset_not_found(
        self,
    ) -> None:
        rest_client = _planner_rest_client([_Page([])])

        with pytest.raises(DatasetNotFoundError) as exc_info:
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
                name="Missing",
                to_project="B",
            )
        assert "Missing" in str(exc_info.value)

    def test_build_dataset_plan__source_name_resolves_to_many__raises_conflict(
        self,
    ) -> None:
        # Workspace uniqueness is enforced by the BE (UNIQUE
        # (workspace_id, name)); if the BE invariant is somehow
        # violated, surface it as ConflictError rather than silently
        # picking a row.
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

        with pytest.raises(ConflictError):
            planner_module.build_dataset_plan(
                client=_planner_client(rest_client),
                name="MyDataset",
                to_project="B",
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
            )
        message = str(exc_info.value)
        assert "Beta" in message
        # difflib should surface the close one-letter neighbours.
        assert "Did you mean" in message
        assert "Beat" in message or "Best" in message


# ---------------------------------------------------------------------------
# OPIK-7162 + OPIK-7168 integration: resume reuses the temp destination and
# finishes the pending handoff (rename source -> _v1, promote temp -> original).
# ---------------------------------------------------------------------------


class TestBuildResumePlanTempDest:
    def _checkpoint(self) -> MigrationCheckpoint:
        return MigrationCheckpoint(
            key="k",
            workspace="ws",
            project="B",
            dataset="MyDataset",
            path=Path("/tmp/does-not-matter.json"),
            dataset_phase_done=True,
            source_dataset_id="src-1",
            source_name="MyDataset",
            temp_dest_name="MyDataset__migrating",
        )

    def _resume_client(self, source_id: str = "src-1") -> MagicMock:
        # resolve_source(MyDataset) -> the still-unrenamed source;
        # get_dataset(MyDataset__migrating) -> the temp destination.
        rest_client = _planner_rest_client(
            [_Page([_DatasetRow(id=source_id, name="MyDataset")])]
        )
        client = _planner_client(rest_client)
        dest = MagicMock()
        dest.id = "temp-dest-1"
        client.get_dataset = MagicMock(return_value=dest)
        return client

    def test_resume__reuses_temp_and_appends_cascade_then_handoff(self) -> None:
        # A dataset_phase_done checkpoint means create-temp/replay/optimizations
        # already ran into MyDataset__migrating and the source still holds its
        # original name. The resume plan must NOT re-create or re-replay; it
        # resolves the temp destination, then emits the pending tail:
        # CascadeExperiments -> RenameSource -> PromoteDestination.
        with patch.object(
            planner_module, "reconstruct_remaps", return_value=ReconstructedRemaps()
        ):
            plan = planner_module.build_dataset_plan(
                client=self._resume_client(),
                name="MyDataset",
                to_project="B",
                resume_checkpoint=self._checkpoint(),
            )

        types = [type(a).__name__ for a in plan.actions]
        assert types == ["CascadeExperiments", "RenameSource", "PromoteDestination"]
        assert plan.is_resume is True
        # The cascade + promote target the TEMP destination (promote hasn't run
        # yet); the source rename moves the original name to _v1.
        cascade = plan.actions[0]
        assert cascade.dest_name == "MyDataset__migrating"
        rename = plan.actions[1]
        assert rename.from_name == "MyDataset"
        assert rename.to_name == "MyDataset_v1"
        promote = plan.actions[2]
        assert promote.from_name == "MyDataset__migrating"
        assert promote.to_name == "MyDataset"

    def test_resume__source_id_mismatch__raises(self) -> None:
        # If the user-supplied name now resolves to a DIFFERENT dataset than the
        # interrupted run's source, resume must refuse rather than migrate the
        # wrong dataset.
        with patch.object(
            planner_module, "reconstruct_remaps", return_value=ReconstructedRemaps()
        ):
            with pytest.raises(MigrationError, match="different dataset"):
                planner_module.build_dataset_plan(
                    client=self._resume_client(source_id="DIFFERENT-id"),
                    name="MyDataset",
                    to_project="B",
                    resume_checkpoint=self._checkpoint(),
                )
