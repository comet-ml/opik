"""Tests for CLI commands."""

from pathlib import Path
from unittest.mock import MagicMock, patch
from contextlib import contextmanager

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


class TestSmokeTestCommand:
    """Test the smoke-test CLI command."""

    def test_smoke_test_help(self):
        """Test that the smoke-test command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["smoke-test", "--help"])
        assert result.exit_code == 0
        assert "smoke-test" in result.output
        assert "--project-name" in result.output
        assert "Project name for the smoke test" in result.output
        assert "WORKSPACE" in result.output
        assert "Run a smoke test to verify Opik integration" in result.output

    def test_smoke_test_minimal_args_parsing(self):
        """Test that smoke-test command parses minimal required arguments correctly."""
        runner = CliRunner()
        # Test that help shows workspace is required
        result = runner.invoke(cli, ["smoke-test", "--help"])
        assert result.exit_code == 0
        assert "WORKSPACE" in result.output
        # Test that missing workspace causes error
        result = runner.invoke(cli, ["smoke-test"])
        assert result.exit_code != 0
        assert "Missing argument" in result.output or "Error" in result.output

    @patch("opik.api_objects.opik_client.get_client_cached")
    @patch("opik.cli.smoke_test.cli.opik.Opik")
    @patch("opik.cli.smoke_test.cli.opik.start_as_current_trace")
    @patch("opik.cli.smoke_test.cli.opik_context.update_current_trace")
    @patch("opik.cli.smoke_test.cli.opik_context.update_current_span")
    @patch("opik.cli.smoke_test.cli.create_opik_logo_image")
    @patch("opik.cli.smoke_test.cli.track")
    def test_smoke_test_with_workspace_and_project_name(
        self,
        mock_track,
        mock_create_logo,
        mock_update_span,
        mock_update_trace,
        mock_start_trace,
        mock_opik_class,
        mock_get_client_cached,
    ):
        """Test smoke-test command with workspace and --project-name arguments."""
        # Setup mocks
        mock_client = MagicMock()
        mock_opik_class.return_value = mock_client

        mock_cached_client = MagicMock()
        mock_get_client_cached.return_value = mock_cached_client

        # Mock the context manager for start_as_current_trace
        @contextmanager
        def mock_trace_context(*args, **kwargs):
            yield MagicMock()

        mock_start_trace.return_value = mock_trace_context()

        # Mock create_opik_logo_image to return a fake path
        mock_logo_path = Path("/tmp/fake_logo.png")
        mock_create_logo.return_value = mock_logo_path

        # Mock the track decorator to return the function unchanged
        def track_decorator(*args, **kwargs):
            def decorator(func):
                return func

            return decorator

        mock_track.side_effect = track_decorator

        # Run the command
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["smoke-test", "test-workspace", "--project-name", "test-project"],
            catch_exceptions=False,
        )

        # Assertions
        assert result.exit_code == 0
        # Verify client was created with correct arguments
        mock_opik_class.assert_called_once()
        call_kwargs = mock_opik_class.call_args[1]
        assert call_kwargs["workspace"] == "test-workspace"
        assert call_kwargs["project_name"] == "test-project"
        # Verify trace was started
        mock_start_trace.assert_called_once()
        # Verify cached client is NOT flushed (since _temporary_client_context patches
        # get_client_cached to return mock_client, not mock_cached_client)
        mock_cached_client.flush.assert_not_called()
        # Verify explicit client was flushed and ended
        # Note: _temporary_client_context patches get_client_cached to return mock_client,
        # so when start_as_current_trace calls get_client_cached().flush(), it flushes mock_client
        mock_client.flush.assert_called_once()
        mock_client.end.assert_called_once()

    @patch("opik.api_objects.opik_client.get_client_cached")
    @patch("opik.cli.smoke_test.cli.opik.Opik")
    @patch("opik.cli.smoke_test.cli.opik.start_as_current_trace")
    @patch("opik.cli.smoke_test.cli.opik_context.update_current_trace")
    @patch("opik.cli.smoke_test.cli.opik_context.update_current_span")
    @patch("opik.cli.smoke_test.cli.create_opik_logo_image")
    @patch("opik.cli.smoke_test.cli.track")
    def test_smoke_test_with_default_project_name(
        self,
        mock_track,
        mock_create_logo,
        mock_update_span,
        mock_update_trace,
        mock_start_trace,
        mock_opik_class,
        mock_get_client_cached,
    ):
        """Test smoke-test command with workspace only (uses default project name)."""
        # Setup mocks
        mock_client = MagicMock()
        mock_opik_class.return_value = mock_client

        mock_cached_client = MagicMock()
        mock_get_client_cached.return_value = mock_cached_client

        # Mock the context manager for start_as_current_trace
        @contextmanager
        def mock_trace_context(*args, **kwargs):
            yield MagicMock()

        mock_start_trace.return_value = mock_trace_context()

        # Mock create_opik_logo_image to return a fake path
        mock_logo_path = Path("/tmp/fake_logo.png")
        mock_create_logo.return_value = mock_logo_path

        # Mock the track decorator to return the function unchanged
        def track_decorator(*args, **kwargs):
            def decorator(func):
                return func

            return decorator

        mock_track.side_effect = track_decorator

        # Run the command without --project-name (should use default)
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["smoke-test", "test-workspace"],
            catch_exceptions=False,
        )

        # Assertions
        assert result.exit_code == 0
        # Verify client was created with default project name
        mock_opik_class.assert_called_once()
        call_kwargs = mock_opik_class.call_args[1]
        assert call_kwargs["workspace"] == "test-workspace"
        assert call_kwargs["project_name"] == "smoke-test-project"  # Default value
        # Verify trace was started with default project name
        mock_start_trace.assert_called_once()
        trace_call_kwargs = mock_start_trace.call_args[1]
        assert trace_call_kwargs["project_name"] == "smoke-test-project"
