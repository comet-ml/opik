"""Unit tests for ``opik copy dataset``.

Coverage targets:
1. Click surface: --destination-project required; --help works.
2. destination_project plumbing into the import functions threads through
   to the right places (datasets, experiments, traces).
3. Orchestrator behaviour: missing source dataset, --dry-run, --exclude-experiments,
   --source-project filtering, --yes, post-copy count diff, run-dir cleanup.

Tests use Click's CliRunner with a mocked Opik client to avoid hitting any
real backend.
"""

import json
import sys
from pathlib import Path
from typing import Any, Dict
from unittest.mock import MagicMock, Mock, patch

import pytest
from click.testing import CliRunner

# Match the pattern used by sibling test_import_experiment.py
sys.modules.setdefault("opik.api_objects.prompt.prompt", MagicMock())

from opik import exceptions  # noqa: E402
from opik.cli.copy.dataset import (  # noqa: E402
    _filter_experiments_by_source_project,
    _make_run_dir,
    _scan_run_dir,
)
from opik.cli.imports.dataset import import_datasets_from_directory  # noqa: E402
from opik.cli.imports.experiment import (  # noqa: E402
    _import_traces_from_projects_directory,
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
        "downloaded_at": "2026-04-29T00:00:00",
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
            {
                "id": f"{trace_id}-s2",
                "name": "s2",
                "parent_span_id": f"{trace_id}-s1",
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


def _make_mock_client() -> Mock:
    client = Mock()
    client.flush = Mock(return_value=True)
    client.create_dataset = Mock()
    client.get_dataset = Mock()
    client.get_dataset_experiments = Mock(return_value=[])
    return client


# ---------------------------------------------------------------------------
# 1. Click surface
# ---------------------------------------------------------------------------


class TestClickSurface:
    """Verify the CLI surface: required flags, help text, registration."""

    def test_copy_group_registered(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["copy", "--help"])
        assert result.exit_code == 0
        assert "Copy assets between projects" in result.output

    def test_copy_dataset_help(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["copy", "ws", "dataset", "--help"])
        assert result.exit_code == 0
        assert "--destination-project" in result.output
        assert "--source-project" in result.output
        assert "--exclude-experiments" in result.output
        assert "--dry-run" in result.output
        assert "--yes" in result.output

    def test_destination_project_required(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["copy", "ws", "dataset", "MyDataset"])
        assert result.exit_code == 2
        assert "--destination-project" in result.output

    def test_copy_group_without_subcommand_errors(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["copy", "ws"])
        assert result.exit_code == 2
        assert "Missing ITEM" in result.output
        assert "dataset" in result.output


# ---------------------------------------------------------------------------
# 2. destination_project plumbing
# ---------------------------------------------------------------------------


class TestDestinationProjectPlumbing:
    """Confirm the override flows through to the underlying SDK calls."""

    def test_import_datasets_passes_destination_project_to_get_or_create(
        self, tmp_path: Path
    ) -> None:
        _write_dataset_export(tmp_path, "Foo", item_count=2)
        client = Mock()
        new_dataset = Mock()
        client.get_or_create_dataset.return_value = new_dataset

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path / "datasets",
            dry_run=False,
            name_pattern=None,
            debug=False,
            destination_project="DestProj",
        )

        client.get_or_create_dataset.assert_called_once_with(
            name="Foo", project_name="DestProj"
        )
        new_dataset.insert.assert_called_once()

    def test_import_datasets_no_override_preserves_old_behavior(
        self, tmp_path: Path
    ) -> None:
        """Regression guard: destination_project=None must not break opik import."""
        _write_dataset_export(tmp_path, "Foo", item_count=1)
        client = Mock()
        client.get_or_create_dataset.return_value = Mock()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path / "datasets",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # No project_name kwarg → SDK falls back to its default behaviour.
        client.get_or_create_dataset.assert_called_once_with(
            name="Foo", project_name=None
        )

    def test_import_datasets_propagates_sdk_errors(self, tmp_path: Path) -> None:
        """SDK errors (auth, network, permissions) raised by
        ``get_or_create_dataset`` must surface as a per-file error rather than
        being silently swallowed — replaces the prior get/except/create dance."""

        class FakeAuthError(Exception):
            pass

        _write_dataset_export(tmp_path, "Foo", item_count=1)
        client = Mock()
        client.get_or_create_dataset.side_effect = FakeAuthError("403 forbidden")

        result = import_datasets_from_directory(
            client=client,
            source_dir=tmp_path / "datasets",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        assert result["datasets_errors"] == 1

    def test_traces_use_destination_project_when_override_set(
        self, tmp_path: Path
    ) -> None:
        _write_trace_export(tmp_path, project="SourceProj", trace_id="t1")
        client = Mock()
        new_trace = Mock(id="new-t1")
        client.trace = Mock(return_value=new_trace)
        new_span = Mock(id="new-s")
        client.span = Mock(return_value=new_span)

        _import_traces_from_projects_directory(
            client=client,
            workspace_root=tmp_path,
            dry_run=False,
            debug=False,
            destination_project="DestProj",
        )

        # Every trace + span call must land in DestProj, not SourceProj.
        assert client.trace.call_args.kwargs["project_name"] == "DestProj"
        for call in client.span.call_args_list:
            assert call.kwargs["project_name"] == "DestProj"

    def test_traces_no_override_uses_project_dir_name(self, tmp_path: Path) -> None:
        """Regression guard: existing opik import behaviour preserved."""
        _write_trace_export(tmp_path, project="MyProj", trace_id="t1")
        client = Mock()
        client.trace = Mock(return_value=Mock(id="new-t1"))
        client.span = Mock(return_value=Mock(id="new-s"))

        _import_traces_from_projects_directory(
            client=client,
            workspace_root=tmp_path,
            dry_run=False,
            debug=False,
        )

        assert client.trace.call_args.kwargs["project_name"] == "MyProj"


# ---------------------------------------------------------------------------
# 3. Orchestrator helpers
# ---------------------------------------------------------------------------


class TestOrchestratorHelpers:
    def test_make_run_dir_is_stable_for_same_args(self, tmp_path: Path) -> None:
        """Re-running the same copy command must hit the same run dir so the
        ``MigrationManifest`` can resume from where the previous run left off."""
        with patch("opik.cli.copy.dataset.COPY_RUNS_DIR", tmp_path):
            d1 = _make_run_dir(
                workspace="ws",
                dataset_name="MyDataset",
                destination_project="DestProj",
            )
            d2 = _make_run_dir(
                workspace="ws",
                dataset_name="MyDataset",
                destination_project="DestProj",
            )
            assert d1 == d2
            assert d1.exists()

    def test_make_run_dir_distinguishes_different_jobs(self, tmp_path: Path) -> None:
        """Genuinely different copy jobs (e.g. different destinations or
        ``--exclude-experiments`` toggled) must get distinct directories so
        their manifests don't collide."""
        with patch("opik.cli.copy.dataset.COPY_RUNS_DIR", tmp_path):
            base_kwargs = dict(workspace="ws", dataset_name="MyDataset")
            d_dest_a = _make_run_dir(**base_kwargs, destination_project="DestA")
            d_dest_b = _make_run_dir(**base_kwargs, destination_project="DestB")
            d_exclude = _make_run_dir(
                **base_kwargs,
                destination_project="DestA",
                exclude_experiments=True,
            )
            d_source = _make_run_dir(
                **base_kwargs,
                destination_project="DestA",
                source_project="ProjA",
            )
            # All four are distinct.
            assert len({d_dest_a, d_dest_b, d_exclude, d_source}) == 4

    def test_scan_run_dir_counts_all_assets(self, tmp_path: Path) -> None:
        ws_root = tmp_path / "ws"
        _write_dataset_export(ws_root, "Foo", item_count=5)
        _write_experiment_export(ws_root, "Exp1", "e1", "ProjA", "Foo")
        _write_trace_export(ws_root, "ProjA", "t1")
        _write_trace_export(ws_root, "ProjA", "t2")

        counts = _scan_run_dir(tmp_path, "ws")

        assert counts == {
            "datasets": 1,
            "dataset_items": 5,
            "experiments": 1,
            "traces": 2,
            "spans": 4,  # 2 spans per trace
        }

    def test_filter_experiments_drops_other_source_projects(
        self, tmp_path: Path
    ) -> None:
        _write_experiment_export(tmp_path, "Keep", "e1", "ProjA", "Foo")
        _write_experiment_export(tmp_path, "Drop", "e2", "ProjB", "Foo")
        _write_trace_export(tmp_path, "ProjA", "t1")
        _write_trace_export(tmp_path, "ProjB", "t2")

        retained = _filter_experiments_by_source_project(tmp_path, "ProjA")

        assert retained == 1
        assert (tmp_path / "experiments" / "experiment_Keep_e1.json").exists()
        assert not (tmp_path / "experiments" / "experiment_Drop_e2.json").exists()
        assert (tmp_path / "projects" / "ProjA").exists()
        assert not (tmp_path / "projects" / "ProjB").exists()

    def test_filter_experiments_keeps_unresolved_project(self, tmp_path: Path) -> None:
        """When metadata.project_name is missing AND no item trace_id matches a
        known project dir, the experiment must be **kept**, not silently dropped."""
        experiments_dir = tmp_path / "experiments"
        experiments_dir.mkdir(parents=True, exist_ok=True)
        with open(experiments_dir / "experiment_Orphan_e1.json", "w") as fh:
            json.dump(
                {
                    "experiment": {
                        "id": "e1",
                        "name": "Orphan",
                        "dataset_name": "Foo",
                        "metadata": {},
                    },
                    "items": [{"trace_id": "trace-not-on-disk"}],
                },
                fh,
            )
        _write_trace_export(tmp_path, "ProjA", "t1")
        _write_experiment_export(tmp_path, "Keep", "e2", "ProjA", "Foo")

        retained = _filter_experiments_by_source_project(tmp_path, "ProjA")

        # Both kept: the matching one AND the unresolved one (defensive default).
        assert retained == 2
        assert (tmp_path / "experiments" / "experiment_Orphan_e1.json").exists()
        assert (tmp_path / "experiments" / "experiment_Keep_e2.json").exists()

    def test_filter_experiments_resolves_via_trace_index(self, tmp_path: Path) -> None:
        """When metadata is missing, the trace→project index must resolve the
        experiment's project via any matching trace_id, not just the first item."""
        experiments_dir = tmp_path / "experiments"
        experiments_dir.mkdir(parents=True, exist_ok=True)
        _write_trace_export(tmp_path, "ProjB", "trace-b")
        with open(experiments_dir / "experiment_Drop_e1.json", "w") as fh:
            json.dump(
                {
                    "experiment": {
                        "id": "e1",
                        "name": "Drop",
                        "dataset_name": "Foo",
                        "metadata": {},
                    },
                    "items": [
                        {"trace_id": "trace-not-on-disk"},
                        {"trace_id": "trace-b"},
                    ],
                },
                fh,
            )

        retained = _filter_experiments_by_source_project(tmp_path, "ProjA")

        # Resolved to ProjB → doesn't match ProjA → dropped.
        assert retained == 0
        assert not (tmp_path / "experiments" / "experiment_Drop_e1.json").exists()


# ---------------------------------------------------------------------------
# 4. End-to-end CLI orchestration (mocked)
# ---------------------------------------------------------------------------


class _FakeExportContext:
    """Stand-in for the export passes — writes the fixture files we expect."""

    def __init__(self, workspace_root_factory):  # type: ignore[no-untyped-def]
        self.workspace_root_factory = workspace_root_factory
        self.dataset_calls = 0
        self.experiment_calls = 0

    def export_dataset(self, name: str, workspace: str, output_path: str, **kwargs):  # type: ignore[no-untyped-def]
        self.dataset_calls += 1
        ws_root = Path(output_path) / workspace
        _write_dataset_export(ws_root, name, item_count=3)

    def export_experiment(
        self, name_or_id: str, workspace: str, output_path: str, **kwargs
    ):  # type: ignore[no-untyped-def]
        self.experiment_calls += 1
        ws_root = Path(output_path) / workspace
        _write_dataset_export(ws_root, kwargs["dataset"], item_count=3)
        _write_experiment_export(
            ws_root, "Exp1", name_or_id, "SourceProj", kwargs["dataset"]
        )
        _write_trace_export(ws_root, "SourceProj", "t-1")


@pytest.fixture
def fake_exports(monkeypatch):  # type: ignore[no-untyped-def]
    fake = _FakeExportContext(workspace_root_factory=None)
    monkeypatch.setattr(
        "opik.cli.copy.dataset.export_dataset_by_name", fake.export_dataset
    )
    monkeypatch.setattr(
        "opik.cli.copy.dataset.export_experiment_by_name_or_id", fake.export_experiment
    )
    return fake


@pytest.fixture
def mock_opik_client(monkeypatch):  # type: ignore[no-untyped-def]
    client = _make_mock_client()
    monkeypatch.setattr("opik.cli.copy.dataset.opik.Opik", lambda **_: client)
    return client


class TestCopyDatasetOrchestrator:
    def test_source_dataset_missing_exits_1(
        self, mock_opik_client: Mock, fake_exports: _FakeExportContext
    ) -> None:
        mock_opik_client.get_dataset.side_effect = exceptions.DatasetNotFound("X")
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["copy", "ws", "dataset", "X", "--destination-project", "DestProj"],
        )
        assert result.exit_code == 1
        assert "not found" in result.output.lower()
        assert fake_exports.dataset_calls == 0

    def test_dry_run_does_not_import(
        self,
        mock_opik_client: Mock,
        fake_exports: _FakeExportContext,
        monkeypatch,  # type: ignore[no-untyped-def]
    ) -> None:
        # Fake "import" sentinels so we can assert they were never invoked.
        ds_import = Mock()
        exp_import = Mock()
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_datasets_from_directory", ds_import
        )
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_experiments_from_directory", exp_import
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "copy",
                "ws",
                "dataset",
                "Foo",
                "--destination-project",
                "DestProj",
                "--exclude-experiments",
                "--dry-run",
            ],
        )

        assert result.exit_code == 0, result.output
        assert "Dry run" in result.output
        ds_import.assert_not_called()
        exp_import.assert_not_called()

    def test_exclude_experiments_skips_experiment_import(
        self,
        mock_opik_client: Mock,
        fake_exports: _FakeExportContext,
        monkeypatch,  # type: ignore[no-untyped-def]
    ) -> None:
        ds_import = Mock(return_value={"datasets": 1})
        exp_import = Mock()
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_datasets_from_directory", ds_import
        )
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_experiments_from_directory", exp_import
        )
        # Verifier returns True so the command exits 0.
        monkeypatch.setattr(
            "opik.cli.copy.dataset._verify_destination_counts", lambda *a, **kw: True
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "copy",
                "ws",
                "dataset",
                "Foo",
                "--destination-project",
                "DestProj",
                "--exclude-experiments",
                "--yes",
            ],
        )

        assert result.exit_code == 0, result.output
        assert ds_import.called
        assert ds_import.call_args.kwargs["destination_project"] == "DestProj"
        exp_import.assert_not_called()
        assert fake_exports.experiment_calls == 0
        assert fake_exports.dataset_calls == 1

    def test_full_copy_threads_destination_into_both_imports(
        self,
        mock_opik_client: Mock,
        fake_exports: _FakeExportContext,
        monkeypatch,  # type: ignore[no-untyped-def]
    ) -> None:
        # Pretend one experiment tags along.
        mock_opik_client.get_dataset_experiments.return_value = [Mock(id="e-1")]

        ds_import = Mock(return_value={"datasets": 1})
        exp_import = Mock(return_value={"experiments": 1})
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_datasets_from_directory", ds_import
        )
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_experiments_from_directory", exp_import
        )
        monkeypatch.setattr(
            "opik.cli.copy.dataset._verify_destination_counts", lambda *a, **kw: True
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "copy",
                "ws",
                "dataset",
                "Foo",
                "--destination-project",
                "DestProj",
                "--yes",
            ],
        )

        assert result.exit_code == 0, result.output
        assert ds_import.call_args.kwargs["destination_project"] == "DestProj"
        assert exp_import.call_args.kwargs["destination_project"] == "DestProj"
        assert fake_exports.experiment_calls == 1

    def test_count_diff_mismatch_exits_1(
        self,
        mock_opik_client: Mock,
        fake_exports: _FakeExportContext,
        monkeypatch,  # type: ignore[no-untyped-def]
    ) -> None:
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_datasets_from_directory",
            Mock(return_value={"datasets": 1}),
        )
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_experiments_from_directory", Mock()
        )
        monkeypatch.setattr(
            "opik.cli.copy.dataset._verify_destination_counts", lambda *a, **kw: False
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "copy",
                "ws",
                "dataset",
                "Foo",
                "--destination-project",
                "DestProj",
                "--exclude-experiments",
                "--yes",
            ],
        )

        assert result.exit_code == 1
        assert "didn't match" in result.output

    def test_user_declines_confirmation_aborts_without_import(
        self,
        mock_opik_client: Mock,
        fake_exports: _FakeExportContext,
        monkeypatch,  # type: ignore[no-untyped-def]
    ) -> None:
        ds_import = Mock()
        monkeypatch.setattr(
            "opik.cli.copy.dataset.import_datasets_from_directory", ds_import
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "copy",
                "ws",
                "dataset",
                "Foo",
                "--destination-project",
                "DestProj",
                "--exclude-experiments",
            ],
            input="n\n",
        )

        assert result.exit_code == 0
        assert "Aborted" in result.output
        ds_import.assert_not_called()
