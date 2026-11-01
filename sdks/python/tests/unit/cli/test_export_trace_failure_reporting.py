"""Unit tests for trace export failure reporting in the export CLI.

HIGH-4  A trace whose fetch fails after retries is printed in red but not
        reported: export_traces_by_ids drops its failed count, so
        `opik export experiment` and `opik export all` still exit 0 and
        print a success summary even though trace files are missing.
"""

from unittest.mock import MagicMock, patch

import pytest

_EXPERIMENT_MODULE = "opik.cli.exports.experiment"
_ALL_MODULE = "opik.cli.exports.all"


def _make_manifest(trace_ids):
    manifest = MagicMock()
    manifest.get_all_trace_ids.return_value = set(trace_ids)
    return manifest


class TestExportTracesByIdsFailureCount:
    def test_export_traces_by_ids__fetch_failure__reports_failed_count(self, tmp_path):
        """A non-retriable fetch error must be reported, not only printed."""
        from opik.cli.exports.experiment import export_traces_by_ids

        client = MagicMock()
        with patch(
            f"{_EXPERIMENT_MODULE}._fetch_trace_data",
            side_effect=RuntimeError("boom"),
        ):
            result = export_traces_by_ids(
                client, ["t1"], tmp_path, "proj", None, "json", False, False
            )

        assert result == (0, 0, 1)

    def test_export_traces_by_ids__partial_failure__keeps_exported_count(
        self, tmp_path
    ):
        """One success and one failure must report each in its own slot."""
        from opik.cli.exports.experiment import export_traces_by_ids

        trace = MagicMock()
        trace.model_dump.return_value = {"id": "ok"}
        client = MagicMock()
        client.get_trace_content.return_value = trace
        client.search_spans.return_value = []

        real_fetch = __import__(
            "opik.cli.exports.experiment", fromlist=["_fetch_trace_data"]
        )._fetch_trace_data

        def fetch(client_, trace_id, project_name, debug):
            if trace_id == "bad":
                raise RuntimeError("boom")
            return real_fetch(client_, trace_id, project_name, debug)

        with patch(f"{_EXPERIMENT_MODULE}._fetch_trace_data", side_effect=fetch):
            result = export_traces_by_ids(
                client, ["ok", "bad"], tmp_path, "proj", None, "json", False, False
            )

        assert result == (1, 0, 1)


class TestExportExperimentReportsTraceFailures:
    def test_export_experiment_by_name__trace_failure__exits_1(self, tmp_path):
        """A failed trace export must make the command exit non-zero."""
        from opik.cli.exports import experiment as experiment_module

        experiment = MagicMock()
        experiment.id = "e1"
        experiment.name = "exp"
        experiment.dataset_name = None
        experiment.get_experiment_data.return_value.prompt_versions = []

        client = MagicMock()
        client.get_experiments_by_name.return_value = [experiment]

        def export_by_id(
            client_,
            output_dir,
            project_name,
            experiment_id,
            max_traces,
            force,
            debug,
            format_,
            trace_ids_collector=None,
            experiment_obj=None,
        ):
            trace_ids_collector.add("t1")
            return {}, 1, None

        with (
            patch(f"{_EXPERIMENT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_EXPERIMENT_MODULE}.prepare_project_export_dir",
                return_value=("pid", tmp_path),
            ),
            patch(
                f"{_EXPERIMENT_MODULE}.export_experiment_by_id",
                side_effect=export_by_id,
            ),
            patch(
                f"{_EXPERIMENT_MODULE}._fetch_trace_data",
                side_effect=RuntimeError("boom"),
            ),
        ):
            with pytest.raises(SystemExit) as exc_info:
                experiment_module.export_experiment_by_name(
                    "exp", "ws", "proj", str(tmp_path), None, None, True, False, "json"
                )

        assert exc_info.value.code == 1

    def test_export_all_experiments__trace_failure__sets_had_errors(self, tmp_path):
        """A failed batch trace export must set had_errors for `export all`."""
        from opik.cli.exports.all import _export_all_experiments

        experiment = MagicMock()
        experiment.id = "e1"
        experiment.name = "exp"
        experiments_dir = tmp_path / "experiments"
        experiments_dir.mkdir()

        client = MagicMock()
        with (
            patch(
                f"{_ALL_MODULE}._paginate_experiments",
                return_value=[experiment],
            ),
            patch(
                f"{_ALL_MODULE}.export_experiment_by_id",
                return_value=({}, 1, _make_manifest({"t1"})),
            ),
            patch(
                f"{_EXPERIMENT_MODULE}._fetch_trace_data",
                side_effect=RuntimeError("boom"),
            ),
        ):
            _, _, _, _, had_errors = _export_all_experiments(
                client=client,
                project_dir=tmp_path,
                project_name="proj",
                project_id=None,
                experiments_dir=experiments_dir,
                max_results=None,
                force=True,
                debug=False,
                format="json",
            )

        assert had_errors is True, (
            "a failed trace export must set had_errors so the CLI exits 1"
        )
