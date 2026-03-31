"""Unit tests for CLI changes: build_import_metadata, _validate_include,
prompt type case-insensitivity, export_traces unlimited pagination,
and 'all' subcommand registration."""

import json
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
import click
from click.testing import CliRunner

from opik.cli import cli
from opik.cli.imports.utils import (
    build_import_metadata,
    _TRACE_IMPORT_FIELDS,
    _SPAN_IMPORT_FIELDS,
    _EXPERIMENT_IMPORT_FIELDS,
)
from opik.api_objects.prompt.types import PromptType


# ---------------------------------------------------------------------------
# build_import_metadata
# ---------------------------------------------------------------------------


class TestBuildImportMetadata:
    def test_build_import_metadata__no_matching_fields_no_existing_metadata__returns_none(
        self,
    ):
        source = {"name": "my-trace"}
        result = build_import_metadata(source, _TRACE_IMPORT_FIELDS, None)
        assert result is None

    def test_build_import_metadata__no_matching_fields_with_existing__returns_existing_unchanged(
        self,
    ):
        source = {"name": "my-trace"}
        existing = {"key": "value"}
        result = build_import_metadata(source, _TRACE_IMPORT_FIELDS, existing)
        assert result is existing

    def test_build_import_metadata__fields_present_no_existing__happyflow(self):
        source = {"created_by": "alice", "created_at": "2024-01-01T00:00:00Z"}
        result = build_import_metadata(source, ["created_by", "created_at"], None)
        assert result == {
            "_import_created_by": "alice",
            "_import_created_at": "2024-01-01T00:00:00Z",
        }

    def test_build_import_metadata__fields_present_merged_with_existing__happyflow(
        self,
    ):
        source = {"created_by": "bob", "ttft": 0.5}
        existing = {"custom_key": "custom_val"}
        result = build_import_metadata(source, ["created_by", "ttft"], existing)
        assert result == {
            "custom_key": "custom_val",
            "_import_created_by": "bob",
            "_import_ttft": 0.5,
        }

    def test_build_import_metadata__none_field_values__are_skipped(self):
        source = {"created_by": None, "last_updated_by": "carol"}
        result = build_import_metadata(source, ["created_by", "last_updated_by"], None)
        assert result == {"_import_last_updated_by": "carol"}

    def test_build_import_metadata__all_none_values_with_existing__returns_existing(
        self,
    ):
        source = {"created_by": None, "created_at": None}
        existing = {"x": 1}
        result = build_import_metadata(source, ["created_by", "created_at"], existing)
        assert result is existing

    def test_build_import_metadata__with_existing__does_not_mutate_existing(self):
        source = {"created_by": "dave"}
        existing = {"orig": "val"}
        build_import_metadata(source, ["created_by"], existing)
        assert existing == {"orig": "val"}

    def test_build_import_metadata__span_fields_subset__happyflow(self):
        source = {
            "created_at": "2024-01-01",
            "created_by": "user",
            "last_updated_at": "2024-01-02",
            "last_updated_by": "user2",
            "ttft": 1.2,
        }
        result = build_import_metadata(source, _SPAN_IMPORT_FIELDS, None)
        assert set(result.keys()) == {
            "_import_created_at",
            "_import_created_by",
            "_import_last_updated_at",
            "_import_last_updated_by",
            "_import_ttft",
        }

    def test_build_import_metadata__experiment_fields_subset__happyflow(self):
        source = {
            "created_at": "2024-01-01",
            "created_by": "user",
            "last_updated_at": "2024-01-02",
            "last_updated_by": "user2",
        }
        result = build_import_metadata(source, _EXPERIMENT_IMPORT_FIELDS, None)
        assert len(result) == 4
        assert "_import_created_by" in result


# ---------------------------------------------------------------------------
# _validate_include (export side)
# ---------------------------------------------------------------------------


class TestValidateIncludeExport:
    """Tests for _validate_include callback in exports/all.py."""

    def test_valid_include_option_accepted(self):
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["export", "default", "all", "--include", "datasets,prompts", "--help"],
        )
        # --help always exits 0 regardless of option values
        assert result.exit_code == 0

    def test_invalid_include_option_raises_error(self):
        runner = CliRunner()
        with patch("opik.cli.exports.all.opik.Opik"):
            result = runner.invoke(
                cli,
                ["export", "default", "all", "--include", "invalid_type"],
            )
        assert result.exit_code != 0
        assert "Invalid" in result.output or "invalid" in result.output.lower()

    def test_case_insensitive_include(self):
        """_validate_include normalises to lower-case; upper-case valid values pass."""
        from opik.cli.exports.all import _validate_include

        ctx = MagicMock(spec=click.Context)
        param = MagicMock(spec=click.Parameter)
        result = _validate_include(ctx, param, "Datasets,PROMPTS")
        assert result == ["datasets", "prompts"]

    def test_all_valid_types_accepted(self):
        from opik.cli.exports.all import _validate_include

        ctx = MagicMock(spec=click.Context)
        param = MagicMock(spec=click.Parameter)
        result = _validate_include(ctx, param, "datasets,prompts,projects,experiments")
        assert set(result) == {"datasets", "prompts", "projects", "experiments"}

    def test_invalid_type_raises_bad_parameter(self):
        from opik.cli.exports.all import _validate_include

        ctx = MagicMock(spec=click.Context)
        param = MagicMock(spec=click.Parameter)
        with pytest.raises(click.BadParameter, match="Invalid"):
            _validate_include(ctx, param, "datasets,unknown")


# ---------------------------------------------------------------------------
# _validate_include (import side)
# ---------------------------------------------------------------------------


class TestValidateIncludeImport:
    """Tests for _validate_include callback in imports/all.py."""

    def test_valid_include_types(self):
        from opik.cli.imports.all import _validate_include

        ctx = MagicMock(spec=click.Context)
        param = MagicMock(spec=click.Parameter)
        result = _validate_include(ctx, param, "datasets,experiments")
        assert result == ["datasets", "experiments"]

    def test_invalid_include_raises_bad_parameter(self):
        from opik.cli.imports.all import _validate_include

        ctx = MagicMock(spec=click.Context)
        param = MagicMock(spec=click.Parameter)
        with pytest.raises(click.BadParameter, match="Invalid"):
            _validate_include(ctx, param, "traces")

    def test_empty_segments_ignored(self):
        from opik.cli.imports.all import _validate_include

        ctx = MagicMock(spec=click.Context)
        param = MagicMock(spec=click.Parameter)
        # Leading/trailing commas and spaces
        result = _validate_include(ctx, param, " datasets , , prompts ")
        assert result == ["datasets", "prompts"]


# ---------------------------------------------------------------------------
# Prompt type case-insensitivity fix
# ---------------------------------------------------------------------------


class TestPromptTypeResolution:
    """Verify that the import correctly handles UPPERCASE prompt type strings."""

    def test_uppercase_mustache_resolves(self):
        assert PromptType("MUSTACHE".lower()) == PromptType.MUSTACHE

    def test_uppercase_jinja2_resolves(self):
        assert PromptType("JINJA2".lower()) == PromptType.JINJA2

    def test_lowercase_mustache_resolves(self):
        assert PromptType("mustache") == PromptType.MUSTACHE

    def test_import_prompts_handles_uppercase_type(self):
        """End-to-end: import_prompts_from_directory resolves 'MUSTACHE' type."""
        from opik.cli.imports.prompt import import_prompts_from_directory

        prompt_data = {
            "name": "test-prompt",
            "current_version": {
                "prompt": "Hello {{name}}",
                "type": "MUSTACHE",
                "template_structure": "text",
                "metadata": None,
            },
        }

        mock_client = MagicMock()
        mock_prompt = MagicMock()
        mock_client.create_prompt.return_value = mock_prompt

        with tempfile.TemporaryDirectory() as tmp:
            prompt_file = Path(tmp) / "prompt_test.json"
            prompt_file.write_text(json.dumps(prompt_data))

            with patch("opik.cli.imports.prompt.Prompt"):
                result = import_prompts_from_directory(
                    client=mock_client,
                    source_dir=Path(tmp),
                    dry_run=False,
                    name_pattern=None,
                    debug=False,
                )

            # Should have imported one prompt, not skipped it
            assert result.get("prompts", 0) == 1
            assert result.get("prompts_skipped", 0) == 0

    def test_import_prompts_handles_unknown_type_falls_back_to_mustache(self):
        """Unknown prompt type falls back to MUSTACHE."""
        from opik.cli.imports.prompt import import_prompts_from_directory

        prompt_data = {
            "name": "test-prompt2",
            "current_version": {
                "prompt": "Hello",
                "type": "COMPLETELY_UNKNOWN_TYPE",
                "template_structure": "text",
                "metadata": None,
            },
        }

        mock_client = MagicMock()

        with tempfile.TemporaryDirectory() as tmp:
            prompt_file = Path(tmp) / "prompt_test2.json"
            prompt_file.write_text(json.dumps(prompt_data))

            with patch("opik.cli.imports.prompt.Prompt"):
                result = import_prompts_from_directory(
                    client=mock_client,
                    source_dir=Path(tmp),
                    dry_run=False,
                    name_pattern=None,
                    debug=False,
                )

            # Falls back to MUSTACHE → still imports successfully
            assert result.get("prompts", 0) == 1


# ---------------------------------------------------------------------------
# export_traces: max_results=None fetches all pages
# ---------------------------------------------------------------------------


class TestExportTracesMaxResultsNone:
    """Verify that passing max_results=None to export_traces exhausts all pages."""

    def _make_mock_trace(self, trace_id: str) -> MagicMock:
        t = MagicMock()
        t.id = trace_id
        t.name = f"trace-{trace_id}"
        t.model_dump.return_value = {
            "id": trace_id,
            "name": f"trace-{trace_id}",
            "start_time": None,
            "end_time": None,
            "input": {},
            "output": {},
            "metadata": {},
            "tags": [],
            "feedback_scores": [],
            "error_info": None,
            "thread_id": None,
            "created_at": None,
            "created_by": None,
            "last_updated_at": None,
            "last_updated_by": None,
            "visibility_mode": None,
            "ttft": None,
            "project_name": "test-project",
        }
        return t

    def _make_page(self, traces):
        page = MagicMock()
        page.content = traces
        return page

    def test_all_traces_exported_when_max_results_is_none(self):
        """When max_results=None every trace on the page is exported (no early stop)."""
        from opik.cli.exports.project import export_traces

        traces = [self._make_mock_trace(f"t{i}") for i in range(3)]

        mock_client = MagicMock()
        # Single page of 3 traces (< page_size=100, so loop exits after this page)
        mock_client.rest_client.traces.get_traces_by_project.return_value = (
            self._make_page(traces)
        )
        mock_client.search_spans.return_value = []

        with tempfile.TemporaryDirectory() as tmp:
            exported, skipped, had_errors = export_traces(
                client=mock_client,
                project_name="test-project",
                project_dir=Path(tmp),
                max_results=None,
                filter_string=None,
            )

        assert exported == 3
        assert skipped == 0
        assert had_errors is False

    def test_max_results_limits_export(self):
        """When max_results=1 only 1 trace is requested from the API."""
        from opik.cli.exports.project import export_traces

        # With max_results=1, current_page_size=min(100,1)=1, so API returns at most 1
        traces = [self._make_mock_trace("t1")]

        mock_client = MagicMock()
        mock_client.rest_client.traces.get_traces_by_project.return_value = (
            self._make_page(traces)
        )
        mock_client.search_spans.return_value = []

        with tempfile.TemporaryDirectory() as tmp:
            exported, skipped, had_errors = export_traces(
                client=mock_client,
                project_name="test-project",
                project_dir=Path(tmp),
                max_results=1,
                filter_string=None,
            )

        assert exported == 1
        # Verify the API was called with size=1 (respects max_results)
        call_kwargs = mock_client.rest_client.traces.get_traces_by_project.call_args[1]
        assert call_kwargs["size"] == 1


# ---------------------------------------------------------------------------
# CLI 'all' subcommand registration
# ---------------------------------------------------------------------------


class TestAllCommandRegistered:
    def test_export_all_help_is_accessible(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "default", "all", "--help"])
        assert result.exit_code == 0
        assert "all" in result.output.lower()
        assert "--include" in result.output

    def test_import_all_help_is_accessible(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "default", "all", "--help"])
        assert result.exit_code == 0
        assert "all" in result.output.lower()
        assert "--include" in result.output

    def test_export_group_help_lists_all(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "--help"])
        assert result.exit_code == 0
        assert "all" in result.output

    def test_import_group_help_lists_all(self):
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "--help"])
        assert result.exit_code == 0
        assert "all" in result.output

    def test_export_missing_subcommand_error_mentions_all(self):
        """When no subcommand is given the error message should list 'all'."""
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "default"])
        # Non-zero exit or the error message includes "all"
        assert "all" in result.output


# ---------------------------------------------------------------------------
# _export_all_experiments: semaphore callback prevents deadlock
# ---------------------------------------------------------------------------


class TestExportAllExperimentsSemaphore:
    """Verify the semaphore done_callback in _export_all_experiments prevents deadlock."""

    def test_semaphore_callback_prevents_deadlock__more_than_capacity_experiments__all_exported(
        self,
    ):
        """Submitting N > max_workers*2 experiments must complete without deadlock.

        With max_workers=2 the semaphore capacity is 4.  Submitting 6 experiments
        would block the submission loop forever (the as_completed drain never
        starts) unless the done_callback on each future releases the semaphore.
        """
        from opik.cli.exports.all import _export_all_experiments
        from types import SimpleNamespace

        num_experiments = 6
        max_workers = 2  # semaphore capacity = max_workers * 2 = 4; 6 > 4

        experiments = [
            SimpleNamespace(id=f"exp-{i}", name=f"experiment-{i}")
            for i in range(num_experiments)
        ]

        def fake_export_by_id(*args, **kwargs):
            return (
                {
                    "datasets": 0,
                    "datasets_skipped": 0,
                    "prompts": 0,
                    "prompts_skipped": 0,
                    "traces": 0,
                    "traces_skipped": 0,
                },
                1,
                None,
            )

        with tempfile.TemporaryDirectory() as tmp:
            workspace_root = Path(tmp)
            experiments_dir = workspace_root / "experiments"
            experiments_dir.mkdir()

            with (
                patch(
                    "opik.cli.exports.all._paginate_experiments",
                    return_value=iter(experiments),
                ),
                patch(
                    "opik.cli.exports.all.export_experiment_by_id",
                    side_effect=fake_export_by_id,
                ),
                patch(
                    "opik.cli.exports.all.export_collected_trace_ids",
                    return_value=(0, 0),
                ),
            ):
                (
                    exp_exported,
                    exp_skipped,
                    traces_exported,
                    traces_skipped,
                    _had_errors,
                ) = _export_all_experiments(
                    client=MagicMock(),
                    workspace_root=workspace_root,
                    experiments_dir=experiments_dir,
                    max_results=None,
                    force=False,
                    debug=False,
                    format="json",
                    max_workers=max_workers,
                )

        # If the semaphore callback was missing the test would hang before this line.
        assert exp_exported + exp_skipped == num_experiments
        assert exp_exported == num_experiments
