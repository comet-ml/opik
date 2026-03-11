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
    """Test the smoke-test functionality via healthcheck command."""

    def test_smoke_test_help(self):
        """Test that the healthcheck --smoke-test command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["healthcheck", "--help"])
        assert result.exit_code == 0
        assert "--smoke-test" in result.output
        assert "--project-name" in result.output
        assert "Project name for the smoke test" in result.output
        assert "WORKSPACE" in result.output
        assert "Run a smoke test to verify Opik integration" in result.output

    def test_smoke_test_minimal_args_parsing(self):
        """Test that healthcheck --smoke-test command requires workspace value."""
        runner = CliRunner()
        # Test that help shows workspace is required
        result = runner.invoke(cli, ["healthcheck", "--help"])
        assert result.exit_code == 0
        assert "WORKSPACE" in result.output
        # Test that missing workspace value causes error
        result = runner.invoke(cli, ["healthcheck", "--smoke-test"])
        assert result.exit_code != 0
        assert "Error" in result.output or "Missing" in result.output

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.api_objects.opik_client.get_client_cached")
    @patch("opik.cli.healthcheck.smoke_test.opik.Opik")
    @patch("opik.cli.healthcheck.smoke_test.opik.start_as_current_trace")
    @patch("opik.cli.healthcheck.smoke_test.opik_context.update_current_trace")
    @patch("opik.cli.healthcheck.smoke_test.opik_context.update_current_span")
    @patch("opik.cli.healthcheck.smoke_test.create_opik_logo_image")
    @patch("opik.cli.healthcheck.smoke_test.track")
    def test_smoke_test_with_workspace_and_project_name(
        self,
        mock_track,
        mock_create_logo,
        mock_update_span,
        mock_update_trace,
        mock_start_trace,
        mock_opik_class,
        mock_get_client_cached,
        mock_healthcheck_run,
    ):
        """Test healthcheck --smoke-test command with workspace and --project-name arguments."""
        # Setup mocks
        mock_client = MagicMock()
        # Mock search_traces to raise an exception immediately to skip verification polling
        # This prevents the verification from running and triggering real client creation
        mock_client.search_traces = MagicMock(
            side_effect=AttributeError("Mock client - search_traces not available")
        )
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
            [
                "healthcheck",
                "--smoke-test",
                "test-workspace",
                "--project-name",
                "test-project",
            ],
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

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.api_objects.opik_client.get_client_cached")
    @patch("opik.cli.healthcheck.smoke_test.opik.Opik")
    @patch("opik.cli.healthcheck.smoke_test.opik.start_as_current_trace")
    @patch("opik.cli.healthcheck.smoke_test.opik_context.update_current_trace")
    @patch("opik.cli.healthcheck.smoke_test.opik_context.update_current_span")
    @patch("opik.cli.healthcheck.smoke_test.create_opik_logo_image")
    @patch("opik.cli.healthcheck.smoke_test.track")
    def test_smoke_test_with_default_project_name(
        self,
        mock_track,
        mock_create_logo,
        mock_update_span,
        mock_update_trace,
        mock_start_trace,
        mock_opik_class,
        mock_get_client_cached,
        mock_healthcheck_run,
    ):
        """Test healthcheck --smoke-test command with workspace only (uses default project name)."""
        # Setup mocks
        mock_client = MagicMock()
        # Mock search_traces to raise an exception immediately to skip verification polling
        # This prevents the verification from running and triggering real client creation
        mock_client.search_traces = MagicMock(
            side_effect=AttributeError("Mock client - search_traces not available")
        )
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
            ["healthcheck", "--smoke-test", "test-workspace"],
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


class TestCheckPermissionsCommand:
    """Test the --check-permissions functionality via healthcheck command."""

    def test_check_permissions_option__in_healthcheck_help__shown(self):
        """Test that --check-permissions option is shown in healthcheck help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["healthcheck", "--help"])
        assert result.exit_code == 0
        assert "--check-permissions" in result.output
        assert "WORKSPACE" in result.output

    def test_check_permissions__missing_workspace__raises_error(self):
        """Test that --check-permissions without a workspace value raises an error."""
        runner = CliRunner()
        result = runner.invoke(cli, ["healthcheck", "--check-permissions"])
        assert result.exit_code != 0
        assert "Error" in result.output or "Missing" in result.output

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.cli.check_user_permissions.run")
    def test_check_permissions__workspace_provided__calls_run_with_workspace(
        self,
        mock_permissions_run,
        mock_standard_run,
    ):
        """Test that --check-permissions pass the workspace to check_user_permissions.run."""
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["healthcheck", "--check-permissions", "my-workspace"],
            catch_exceptions=False,
        )

        assert result.exit_code == 0
        mock_permissions_run.assert_called_once_with(
            api_key=None, workspace="my-workspace"
        )

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.cli.check_user_permissions.run")
    def test_check_permissions__api_key_in_cli_context__passes_api_key_to_run(
        self,
        mock_permissions_run,
        mock_standard_run,
    ):
        """Test that --check-permissions forward the api_key from CLI context."""
        from opik.cli import cli as opik_cli

        runner = CliRunner()
        result = runner.invoke(
            opik_cli,
            [
                "--api-key",
                "test-api-key",
                "healthcheck",
                "--check-permissions",
                "my-workspace",
            ],
            catch_exceptions=False,
        )

        assert result.exit_code == 0
        mock_permissions_run.assert_called_once_with(
            api_key="test-api-key", workspace="my-workspace"
        )

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.cli.check_user_permissions.run")
    def test_check_permissions__empty_workspace__raises_bad_parameter(
        self,
        mock_permissions_run,
        mock_standard_run,
    ):
        """Test that --check-permissions with an empty workspace string raises BadParameter."""
        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["healthcheck", "--check-permissions", ""],
        )

        assert result.exit_code != 0
        assert (
            "Error" in result.output
            or "requires a non-empty workspace name" in result.output
        )
        mock_permissions_run.assert_not_called()

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.cli.check_user_permissions.run")
    def test_check_permissions__no_flag__not_called(
        self,
        mock_permissions_run,
        mock_standard_run,
    ):
        """Test that check_user_permissions.run is not called when --check-permissions is absent."""
        runner = CliRunner()
        result = runner.invoke(cli, ["healthcheck"], catch_exceptions=False)

        assert result.exit_code == 0
        mock_permissions_run.assert_not_called()

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.check_user_permissions.get_user_permissions")
    @patch("opik.cli.healthcheck.check_user_permissions.config.OpikConfig")
    def test_check_permissions__api_returns_user_and_workspace__displays_info(
        self,
        mock_opik_config,
        mock_get_permissions,
        mock_standard_run,
    ):
        """Test that user and workspace info from API response are printed."""
        mock_config = MagicMock()
        mock_config.api_key = "test-api-key"
        mock_config.workspace = "my-workspace"
        mock_config.url_override = "https://opik.example.com"
        mock_opik_config.return_value = mock_config

        mock_get_permissions.return_value = {
            "user_name": "alice",
            "workspace_name": "my-workspace",
            "permissions": [
                {"permission_name": "read", "permission_value": True},
                {"permission_name": "write", "permission_value": False},
            ],
        }

        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["healthcheck", "--check-permissions", "my-workspace"],
            catch_exceptions=False,
        )

        assert result.exit_code == 0
        assert "alice" in result.output
        assert "my-workspace" in result.output

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.check_user_permissions.get_user_permissions")
    @patch("opik.cli.healthcheck.check_user_permissions.config.OpikConfig")
    def test_check_permissions__connection_error__prints_error(
        self,
        mock_opik_config,
        mock_get_permissions,
        mock_standard_run,
    ):
        """Test that a ConnectionError from the API prints an error message."""
        mock_config = MagicMock()
        mock_config.api_key = "test-api-key"
        mock_config.workspace = "my-workspace"
        mock_config.url_override = "https://opik.example.com"
        mock_opik_config.return_value = mock_config

        mock_get_permissions.side_effect = ConnectionError("Network error: timeout")

        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["healthcheck", "--check-permissions", "my-workspace"],
            catch_exceptions=False,
        )

        assert result.exit_code == 0
        assert "Failed to fetch user permissions" in result.output

    @patch("opik.cli.healthcheck.cli.standard_check.run")
    @patch("opik.cli.healthcheck.check_user_permissions.config.OpikConfig")
    def test_check_permissions__missing_api_key__raises_value_error(
        self,
        mock_opik_config,
        mock_standard_run,
    ):
        """Test that missing api_key (not in CLI context and not in config) raises ValueError."""
        mock_config = MagicMock()
        mock_config.api_key = None
        mock_config.workspace = None
        mock_config.url_override = "https://opik.example.com"
        mock_opik_config.return_value = mock_config

        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["healthcheck", "--check-permissions", "my-workspace"],
        )

        assert result.exit_code != 0
        assert isinstance(result.exception, ValueError)
        assert "API key is required" in str(result.exception)
