"""Unit tests for ``opik import dataset`` flags and plumbing.

Covers:
1. Click surface: ``--destination-project`` and ``--exclude-experiments`` are
   accepted and threaded through to ``_import_by_type``.
2. ``destination_project`` plumbing into the underlying SDK calls
   (``client.get_dataset``, ``client.create_dataset``, ``client.trace``,
   ``client.span``) so dataset items, traces, and spans land in the
   destination project rather than wherever the source export said.
3. The new sibling-experiments branch: when ``--destination-project`` is set
   and a sibling ``experiments/`` directory exists, the dataset import path
   *also* invokes ``import_experiments_from_directory`` with the same
   override (gated off by ``--exclude-experiments``).
4. Regression guards: with no flags, behavior is identical to today
   (no project override, no experiments call).

Tests use Click's CliRunner with a mocked Opik client to avoid hitting any
real backend.
"""

import json
import sys
from pathlib import Path
from typing import Any, Dict
from unittest.mock import MagicMock, Mock, patch

from click.testing import CliRunner

# Match the pattern used by sibling test_import_experiment.py.
sys.modules.setdefault("opik.api_objects.prompt.prompt", MagicMock())

from opik.cli.imports.dataset import import_datasets_from_directory  # noqa: E402
from opik.cli.imports.experiment import (  # noqa: E402
    _import_traces_from_projects_directory,
    recreate_experiments,
)
from opik.cli.main import cli  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _write_dataset_export(workspace_root: Path, name: str, item_count: int) -> None:
    datasets_dir = workspace_root / "datasets"
    datasets_dir.mkdir(parents=True, exist_ok=True)
    payload: Dict[str, Any] = {
        "name": name,
        "description": None,
        "items": [{"q": f"q{i}", "a": f"a{i}"} for i in range(item_count)],
        "downloaded_at": "2026-04-30T00:00:00",
    }
    with open(datasets_dir / f"dataset_{name}.json", "w") as fh:
        json.dump(payload, fh)


def _write_trace_export(workspace_root: Path, project: str, trace_id: str) -> None:
    project_dir = workspace_root / "projects" / project
    project_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "trace": {
            "id": trace_id,
            "name": "tr",
            "input": {},
            "output": {},
        },
        "spans": [
            {
                "id": f"{trace_id}-s1",
                "name": "s1",
                "parent_span_id": None,
                "input": {},
                "output": {},
            },
        ],
        "project_name": project,
    }
    with open(project_dir / f"trace_{trace_id}.json", "w") as fh:
        json.dump(payload, fh)


def _write_experiment_export(
    workspace_root: Path, name: str, exp_id: str, project: str, dataset: str
) -> None:
    experiments_dir = workspace_root / "experiments"
    experiments_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "experiment": {
            "id": exp_id,
            "name": name,
            "dataset_name": dataset,
            "metadata": {"project_name": project},
        },
        "items": [],
    }
    with open(experiments_dir / f"experiment_{name}_{exp_id}.json", "w") as fh:
        json.dump(payload, fh)


# ---------------------------------------------------------------------------
# 1. Click surface
# ---------------------------------------------------------------------------


class TestClickSurface:
    def test_help_lists_new_flags(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "ws", "dataset", "--help"])
        assert result.exit_code == 0
        assert "--destination-project" in result.output
        assert "--exclude-experiments" in result.output

    def test_destination_project_threaded_into_import_by_type(
        self, tmp_path: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.imports._import_by_type") as mocked:
            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "MyDataset",
                    "--path",
                    str(tmp_path),
                    "--destination-project",
                    "DestProj",
                ],
            )
        assert result.exit_code == 0
        mocked.assert_called_once()
        kwargs = mocked.call_args.kwargs
        assert kwargs["destination_project"] == "DestProj"
        assert kwargs["exclude_experiments"] is False

    def test_exclude_experiments_threaded_into_import_by_type(
        self, tmp_path: Path
    ) -> None:
        runner = CliRunner()
        with patch("opik.cli.imports._import_by_type") as mocked:
            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "MyDataset",
                    "--path",
                    str(tmp_path),
                    "--destination-project",
                    "DestProj",
                    "--exclude-experiments",
                ],
            )
        assert result.exit_code == 0
        kwargs = mocked.call_args.kwargs
        assert kwargs["exclude_experiments"] is True

    def test_no_flags_preserves_old_invocation(self, tmp_path: Path) -> None:
        """Regression guard: no flags → destination_project=None,
        exclude_experiments=False (today's behaviour exactly)."""
        runner = CliRunner()
        with patch("opik.cli.imports._import_by_type") as mocked:
            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "MyDataset",
                    "--path",
                    str(tmp_path),
                ],
            )
        assert result.exit_code == 0
        kwargs = mocked.call_args.kwargs
        assert kwargs["destination_project"] is None
        assert kwargs["exclude_experiments"] is False


# ---------------------------------------------------------------------------
# 2. destination_project plumbing into the importers
# ---------------------------------------------------------------------------


class TestDestinationProjectPlumbing:
    def test_dataset_import_passes_destination_project(self, tmp_path: Path) -> None:
        _write_dataset_export(tmp_path, "Foo", item_count=2)
        client = Mock()
        # First lookup raises (dataset doesn't exist) so create_dataset fires.
        client.get_dataset.side_effect = Exception("not found")
        client.create_dataset.return_value = Mock()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path / "datasets",
            dry_run=False,
            name_pattern=None,
            debug=False,
            destination_project="DestProj",
        )

        client.get_dataset.assert_called_once_with("Foo", project_name="DestProj")
        client.create_dataset.assert_called_once_with(
            name="Foo", project_name="DestProj"
        )

    def test_dataset_import_no_override_preserves_old_behavior(
        self, tmp_path: Path
    ) -> None:
        _write_dataset_export(tmp_path, "Foo", item_count=1)
        client = Mock()
        client.get_dataset.side_effect = Exception("not found")
        client.create_dataset.return_value = Mock()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path / "datasets",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.get_dataset.assert_called_once_with("Foo", project_name=None)
        client.create_dataset.assert_called_once_with(name="Foo", project_name=None)

    def test_traces_use_destination_project_when_override_set(
        self, tmp_path: Path
    ) -> None:
        _write_trace_export(tmp_path, project="SourceProj", trace_id="t1")
        client = Mock()
        client.trace.return_value = Mock(id="new-t1")
        client.span.return_value = Mock(id="new-s1")

        _import_traces_from_projects_directory(
            client=client,
            workspace_root=tmp_path,
            dry_run=False,
            debug=False,
            destination_project="DestProj",
        )

        assert client.trace.call_args.kwargs["project_name"] == "DestProj"
        for call in client.span.call_args_list:
            assert call.kwargs["project_name"] == "DestProj"

    def test_traces_no_override_uses_project_dir_name(self, tmp_path: Path) -> None:
        _write_trace_export(tmp_path, project="MyProj", trace_id="t1")
        client = Mock()
        client.trace.return_value = Mock(id="new-t1")
        client.span.return_value = Mock(id="new-s1")

        _import_traces_from_projects_directory(
            client=client,
            workspace_root=tmp_path,
            dry_run=False,
            debug=False,
        )

        assert client.trace.call_args.kwargs["project_name"] == "MyProj"

    def test_recreate_experiments_destination_overrides_project_name(
        self, tmp_path: Path
    ) -> None:
        _write_experiment_export(tmp_path, "Exp1", "e1", "MyProj", "Foo")
        client = Mock()
        with patch(
            "opik.cli.imports.experiment.recreate_experiment", return_value=True
        ) as recreate_mock:
            recreate_experiments(
                client=client,
                project_dir=tmp_path / "experiments",
                project_name="OriginalProj",
                destination_project="DestProj",
            )
        assert recreate_mock.call_args.args[2] == "DestProj"


# ---------------------------------------------------------------------------
# 3. Sibling experiments-import branch in `opik import dataset`
# ---------------------------------------------------------------------------


class TestSiblingExperimentsBranch:
    """When ``--destination-project`` is set and ``experiments/`` is present
    alongside ``datasets/``, ``opik import dataset`` should also run the
    experiments importer with the same project override.
    """

    def test_destination_project_triggers_experiments_import(
        self, tmp_path: Path
    ) -> None:
        _write_dataset_export(tmp_path, "Foo", item_count=1)
        _write_experiment_export(tmp_path, "Exp1", "e1", "ProjA", "Foo")
        _write_trace_export(tmp_path, "ProjA", "t1")

        runner = CliRunner()
        with (
            patch(
                "opik.cli.imports.import_datasets_from_directory",
                return_value={
                    "datasets": 1,
                    "datasets_skipped": 0,
                    "datasets_errors": 0,
                },
            ) as ds_mock,
            patch(
                "opik.cli.imports.import_experiments_from_directory",
                return_value={
                    "experiments": 1,
                    "experiments_skipped": 0,
                    "experiments_errors": 0,
                    "datasets": 0,
                    "datasets_skipped": 0,
                    "datasets_errors": 0,
                    "prompts": 0,
                    "prompts_skipped": 0,
                    "prompts_errors": 0,
                    "traces": 0,
                    "traces_errors": 0,
                },
            ) as exp_mock,
            patch("opik.cli.imports.opik.Opik") as opik_mock,
        ):
            client = Mock()
            client.flush.return_value = True
            client.__internal_api__failed_uploads__ = Mock(return_value=0)
            opik_mock.return_value = client

            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "Foo",
                    "--path",
                    str(tmp_path),
                    "--destination-project",
                    "DestProj",
                ],
            )

        assert result.exit_code == 0, result.output
        ds_mock.assert_called_once()
        assert ds_mock.call_args.kwargs["destination_project"] == "DestProj"
        exp_mock.assert_called_once()
        assert exp_mock.call_args.kwargs["destination_project"] == "DestProj"

    def test_exclude_experiments_skips_experiments_call(self, tmp_path: Path) -> None:
        _write_dataset_export(tmp_path, "Foo", item_count=1)
        _write_experiment_export(tmp_path, "Exp1", "e1", "ProjA", "Foo")
        _write_trace_export(tmp_path, "ProjA", "t1")

        runner = CliRunner()
        with (
            patch(
                "opik.cli.imports.import_datasets_from_directory",
                return_value={
                    "datasets": 1,
                    "datasets_skipped": 0,
                    "datasets_errors": 0,
                },
            ) as ds_mock,
            patch("opik.cli.imports.import_experiments_from_directory") as exp_mock,
            patch("opik.cli.imports.opik.Opik") as opik_mock,
        ):
            client = Mock()
            client.flush.return_value = True
            client.__internal_api__failed_uploads__ = Mock(return_value=0)
            opik_mock.return_value = client

            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "Foo",
                    "--path",
                    str(tmp_path),
                    "--destination-project",
                    "DestProj",
                    "--exclude-experiments",
                ],
            )

        assert result.exit_code == 0, result.output
        ds_mock.assert_called_once()
        exp_mock.assert_not_called()

    def test_no_destination_project_skips_experiments_branch(
        self, tmp_path: Path
    ) -> None:
        """Regression guard: today's plain ``opik import dataset`` must not
        suddenly import experiments just because they happen to sit alongside
        the datasets directory. The experiments-import branch only fires when
        ``--destination-project`` is set."""
        _write_dataset_export(tmp_path, "Foo", item_count=1)
        _write_experiment_export(tmp_path, "Exp1", "e1", "ProjA", "Foo")
        _write_trace_export(tmp_path, "ProjA", "t1")

        runner = CliRunner()
        with (
            patch(
                "opik.cli.imports.import_datasets_from_directory",
                return_value={
                    "datasets": 1,
                    "datasets_skipped": 0,
                    "datasets_errors": 0,
                },
            ) as ds_mock,
            patch("opik.cli.imports.import_experiments_from_directory") as exp_mock,
            patch("opik.cli.imports.opik.Opik") as opik_mock,
        ):
            client = Mock()
            client.flush.return_value = True
            client.__internal_api__failed_uploads__ = Mock(return_value=0)
            opik_mock.return_value = client

            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "Foo",
                    "--path",
                    str(tmp_path),
                ],
            )

        assert result.exit_code == 0, result.output
        ds_mock.assert_called_once()
        # No destination_project kwarg → no override threaded through.
        assert ds_mock.call_args.kwargs["destination_project"] is None
        exp_mock.assert_not_called()

    def test_destination_project_no_experiments_dir_skips_experiments_call(
        self, tmp_path: Path
    ) -> None:
        """No-op when ``experiments/`` is absent — the typical
        ``opik export dataset`` layout. Cross-instance migration path
        should not be perturbed."""
        _write_dataset_export(tmp_path, "Foo", item_count=1)
        # Note: no experiments/ dir written.

        runner = CliRunner()
        with (
            patch(
                "opik.cli.imports.import_datasets_from_directory",
                return_value={
                    "datasets": 1,
                    "datasets_skipped": 0,
                    "datasets_errors": 0,
                },
            ),
            patch("opik.cli.imports.import_experiments_from_directory") as exp_mock,
            patch("opik.cli.imports.opik.Opik") as opik_mock,
        ):
            client = Mock()
            client.flush.return_value = True
            client.__internal_api__failed_uploads__ = Mock(return_value=0)
            opik_mock.return_value = client

            result = runner.invoke(
                cli,
                [
                    "import",
                    "ws",
                    "dataset",
                    "Foo",
                    "--path",
                    str(tmp_path),
                    "--destination-project",
                    "DestProj",
                ],
            )

        assert result.exit_code == 0, result.output
        exp_mock.assert_not_called()
