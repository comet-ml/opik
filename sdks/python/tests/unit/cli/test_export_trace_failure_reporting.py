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

    def test_export_traces_by_ids__write_failure__reports_failed_count(self, tmp_path):
        """A write error must be reported as failed, not silently skipped."""
        from opik.cli.exports.experiment import export_traces_by_ids

        trace = MagicMock()
        trace.model_dump.return_value = {"id": "t1"}
        client = MagicMock()
        client.get_trace_content.return_value = trace
        client.search_spans.return_value = []

        with patch(
            f"{_EXPERIMENT_MODULE}.write_json_data",
            side_effect=OSError("disk full"),
        ):
            result = export_traces_by_ids(
                client, ["t1"], tmp_path, "proj", None, "json", False, False
            )

        assert result == (0, 0, 1)


class TestExportExperimentReportsTraceFailures:
    def test_export_experiment_by_name__trace_failure__exits_1(self, tmp_path, capsys):
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
            patch(f"{_EXPERIMENT_MODULE}.print_export_summary") as summary,
        ):
            with pytest.raises(SystemExit) as exc_info:
                experiment_module.export_experiment_by_name(
                    "exp", "ws", "proj", str(tmp_path), None, None, True, False, "json"
                )

        assert exc_info.value.code == 1
        assert summary.call_args.args[0]["traces_failed"] == 1
        output = capsys.readouterr().out
        assert "Export completed with errors" in output
        assert "Successfully exported" not in output

    def test_export_experiment_by_name_or_id__id_path_trace_failure__exits_1(
        self, tmp_path, capsys
    ):
        """A failed trace export on the direct-ID path must exit non-zero."""
        from opik.cli.exports import experiment as experiment_module

        experiment = MagicMock()
        experiment.id = "e1"
        experiment.name = "exp"
        experiment.dataset_name = None

        client = MagicMock()
        client.get_experiment_by_id.return_value = experiment

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
            patch(f"{_EXPERIMENT_MODULE}.print_export_summary") as summary,
        ):
            with pytest.raises(SystemExit) as exc_info:
                experiment_module.export_experiment_by_name_or_id(
                    "e1", "ws", "proj", str(tmp_path), None, None, True, False, "json"
                )

        assert exc_info.value.code == 1
        assert summary.call_args.args[0]["traces_failed"] == 1
        output = capsys.readouterr().out
        assert "Export completed with errors" in output
        assert "Successfully exported" not in output

    def test_export_experiment_by_name__trace_failure__prints_summary(self, tmp_path):
        """The failure summary must name traces as well as set the exit code."""
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
            patch.object(experiment_module.console, "print") as mock_print,
        ):
            with pytest.raises(SystemExit) as exc_info:
                experiment_module.export_experiment_by_name(
                    "exp", "ws", "proj", str(tmp_path), None, None, True, False, "json"
                )

        assert exc_info.value.code == 1
        printed = [str(call.args[0]) for call in mock_print.call_args_list if call.args]
        assert any(
            "Export completed with errors" in message and "traces" in message
            for message in printed
        )

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
            _, _, _, _, traces_failed, had_errors = _export_all_experiments(
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

        assert traces_failed == 1
        assert had_errors is True, (
            "a failed trace export must set had_errors so the CLI exits 1"
        )


def test_write_failure_keeps_manifest_open_and_retry_fetches_only_missing(tmp_path):
    from opik.cli import export_manifest
    from opik.cli.exports import experiment

    client = MagicMock()
    client.get_trace_content.side_effect = lambda trace_id: MagicMock(
        model_dump=lambda: {"id": trace_id}
    )
    client.search_spans.return_value = []
    manifest = export_manifest.ExportManifest(
        tmp_path / "experiments", filename="manifest_e1.db"
    )
    real_write = experiment.write_json_data

    def write(data, path):
        if path.name == "trace_t2.json":
            raise OSError("disk full")
        return real_write(data, path)

    try:
        with patch.object(experiment, "write_json_data", side_effect=write):
            first = experiment.export_traces_by_ids(
                client,
                ["t1", "t2"],
                tmp_path,
                "proj",
                None,
                "json",
                False,
                False,
                manifest=manifest,
            )
        assert first == (1, 0, 1)
        assert not manifest.is_completed
        assert manifest.load_downloaded_set() == {"t1"}

        # Reopen the on-disk manifest to exercise a separate CLI invocation.
        manifest.close()
        manifest = export_manifest.ExportManifest(
            tmp_path / "experiments", filename="manifest_e1.db"
        )
        client.reset_mock()
        second = experiment.export_traces_by_ids(
            client,
            ["t1", "t2"],
            tmp_path,
            "proj",
            None,
            "json",
            False,
            False,
            manifest=manifest,
        )
        assert second == (1, 1, 0)
        client.get_trace_content.assert_called_once_with("t2")
        assert manifest.is_completed
        assert manifest.load_downloaded_set() == {"t1", "t2"}
    finally:
        manifest.close()


@pytest.mark.parametrize(
    "exported,skipped,failed", [(4, 0, 1), (0, 0, 5), (2, 2, 1), (5, 0, 0)]
)
@pytest.mark.parametrize("format_", ["json", "csv"])
def test_summary_trace_row_includes_failed_count(exported, skipped, failed, format_):
    from opik.cli.exports import utils

    with patch.object(utils.console, "print") as printed:
        utils.print_export_summary(
            {"traces": exported, "traces_skipped": skipped, "traces_failed": failed},
            format_,
        )
    table = printed.call_args_list[1].args[0]
    assert [column.header for column in table.columns] == [
        "Type",
        "Exported",
        "Skipped",
        "Failed",
        "Files",
    ]
    rows = list(zip(*(column._cells for column in table.columns)))
    assert rows[0] == (
        "🔍 Traces",
        str(exported),
        str(skipped) if skipped else "",
        str(failed) if failed else "",
        f"trace_*.{format_}",
    )
    assert rows[-1][3] == (str(failed) if failed else "")
    assert rows[-1][4] == f"{exported + skipped} files"


def test_export_all_passes_failed_trace_count_to_summary(tmp_path):
    import importlib

    all_exports = importlib.import_module(_ALL_MODULE)

    with (
        patch.object(all_exports.opik, "Opik"),
        patch.object(
            all_exports, "prepare_project_export_dir", return_value=("pid", tmp_path)
        ),
        patch.object(
            all_exports, "_export_all_experiments", return_value=(1, 0, 4, 0, 1, True)
        ),
        patch.object(all_exports, "print_export_summary") as summary,
    ):
        with pytest.raises(SystemExit) as exc_info:
            all_exports.export_all(
                "ws", "proj", str(tmp_path), ["experiments"], None, False, False, "json"
            )
    assert exc_info.value.code == 1
    assert summary.call_args.args[0]["traces_failed"] == 1
    assert summary.call_args.args[0]["traces"] == 4
