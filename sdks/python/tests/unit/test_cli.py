"""Tests for CLI commands."""

from click.testing import CliRunner

from opik.cli import cli


class TestDownloadCommand:
    """Test the download CLI command."""

    def test_export_group_help(self):
        """Test that the export group shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "--help"])
        assert result.exit_code == 0
        assert "Export data from Opik workspace" in result.output
        assert "dataset" in result.output
        assert "project" in result.output
        assert "experiment" in result.output

    def test_export_dataset_help(self):
        """Test that the export dataset command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "default", "dataset", "--help"])
        assert result.exit_code == 0
        assert "Export a dataset by exact name" in result.output
        assert "--force" in result.output

    def test_export_project_help(self):
        """Test that the export project command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "default", "project", "--help"])
        assert result.exit_code == 0
        assert "Export a project by name or ID" in result.output
        assert "NAME" in result.output

    def test_export_experiment_help(self):
        """Test that the export experiment command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "default", "experiment", "--help"])
        assert result.exit_code == 0
        assert "Export an experiment by exact name" in result.output
        assert "NAME" in result.output


class TestUploadCommand:
    """Test the upload CLI command."""

    def test_import_group_help(self):
        """Test that the import group shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "--help"])
        assert result.exit_code == 0
        assert "Import data to Opik workspace" in result.output
        assert "dataset" in result.output
        assert "project" in result.output
        assert "experiment" in result.output

    def test_import_dataset_help(self):
        """Test that the import dataset command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "default", "dataset", "--help"])
        assert result.exit_code == 0
        assert "Import datasets from workspace/datasets directory" in result.output
        assert "--dry-run" in result.output

    def test_import_project_help(self):
        """Test that the import project command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "default", "project", "--help"])
        assert result.exit_code == 0
        assert "Import projects from workspace/projects directory" in result.output
        assert "--dry-run" in result.output

    def test_import_experiment_help(self):
        """Test that the import experiment command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "default", "experiment", "--help"])
        assert result.exit_code == 0
        assert (
            "Import experiments from workspace/experiments directory" in result.output
        )
        assert "--dry-run" in result.output
