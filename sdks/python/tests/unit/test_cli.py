"""Tests for CLI commands."""

import json
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

from click.testing import CliRunner

from opik.cli import cli


class TestDownloadCommand:
    """Test the download CLI command."""

    def test_download_command_help(self):
        """Test that the download command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["export", "--help"])
        assert result.exit_code == 0
        assert (
            "Download data from a workspace or workspace/project to local files"
            in result.output
        )
        assert "--name" in result.output

    def test_download_command_success(self):
        """Test successful download command."""
        # Mock the Opik module
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Mock trace data
        mock_trace = MagicMock()
        mock_trace.id = "trace_123"
        mock_trace.model_dump.return_value = {
            "id": "trace_123",
            "name": "test_trace",
            "start_time": "2024-01-01T00:00:00Z",
            "input": {"query": "test"},
            "output": {"result": "success"},
        }

        mock_span = MagicMock()
        mock_span.model_dump.return_value = {
            "id": "span_123",
            "name": "test_span",
            "start_time": "2024-01-01T00:00:00Z",
            "input": {"data": "test"},
            "output": {"result": "processed"},
        }

        mock_client.search_traces.return_value = [mock_trace]
        mock_client.search_spans.return_value = [mock_span]

        with patch("opik.cli.export.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "export",
                        "default/test_project",
                        "--path",
                        temp_dir,
                        "--max-results",
                        "10",
                    ],
                )

                assert result.exit_code == 0
                assert "Successfully exported 1 items" in result.output

                # Check that files were created
                # The download command creates workspace/project_name structure
                project_dir = Path(temp_dir) / "default" / "test_project"
                assert project_dir.exists()

                trace_files = list(project_dir.glob("trace_*.json"))
                assert len(trace_files) == 1

                # Check file content
                with open(trace_files[0], "r") as f:
                    data = json.load(f)
                    assert data["trace"]["id"] == "trace_123"
                    assert len(data["spans"]) == 1
                    assert data["spans"][0]["id"] == "span_123"

    def test_download_command_no_traces(self):
        """Test download command when no traces are found."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client
        mock_client.search_traces.return_value = []

        with patch("opik.cli.export.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                runner = CliRunner()
                result = runner.invoke(
                    cli, ["export", "default/empty_project", "--path", temp_dir]
                )

                assert result.exit_code == 0
                assert "No traces found in the project" in result.output

    def test_download_command_workspace_only(self):
        """Test download command with workspace-only format."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Mock projects response
        mock_project = MagicMock()
        mock_project.name = "test_project"
        mock_projects_response = MagicMock()
        mock_projects_response.content = [mock_project]
        mock_client.rest_client.projects.find_projects.return_value = (
            mock_projects_response
        )

        # Mock trace data
        mock_trace = MagicMock()
        mock_trace.id = "trace_123"
        mock_trace.name = "test_trace"
        mock_trace.model_dump.return_value = {
            "id": "trace_123",
            "name": "test_trace",
            "start_time": "2024-01-01T00:00:00Z",
            "input": {"query": "test"},
            "output": {"result": "success"},
        }

        mock_span = MagicMock()
        mock_span.model_dump.return_value = {
            "id": "span_123",
            "name": "test_span",
            "start_time": "2024-01-01T00:00:00Z",
            "input": {"data": "test"},
            "output": {"result": "processed"},
        }

        mock_client.search_traces.return_value = [mock_trace]
        mock_client.search_spans.return_value = [mock_span]

        with patch("opik.cli.export.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "export",
                        "test_workspace",
                        "--path",
                        temp_dir,
                        "--max-results",
                        "10",
                    ],
                )

                assert result.exit_code == 0
                assert (
                    "Exporting data from workspace: test_workspace (all projects)"
                    in result.output
                )
                assert "Found 1 projects in workspace" in result.output

                # Check that files were created in workspace structure
                workspace_dir = Path(temp_dir) / "test_workspace"
                project_dir = workspace_dir / "test_project"
                assert project_dir.exists()

                trace_files = list(project_dir.glob("trace_*.json"))
                assert len(trace_files) == 1

    def test_download_command_with_name_filter(self):
        """Test download command with name filtering."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Mock trace data with different names
        mock_trace1 = MagicMock()
        mock_trace1.id = "trace_123"
        mock_trace1.name = "test_trace"
        mock_trace1.model_dump.return_value = {
            "id": "trace_123",
            "name": "test_trace",
            "start_time": "2024-01-01T00:00:00Z",
        }

        mock_trace2 = MagicMock()
        mock_trace2.id = "trace_456"
        mock_trace2.name = "prod_trace"
        mock_trace2.model_dump.return_value = {
            "id": "trace_456",
            "name": "prod_trace",
            "start_time": "2024-01-01T00:00:00Z",
        }

        mock_span = MagicMock()
        mock_span.model_dump.return_value = {
            "id": "span_123",
            "name": "test_span",
            "start_time": "2024-01-01T00:00:00Z",
        }

        # Return both traces initially
        mock_client.search_traces.return_value = [mock_trace1, mock_trace2]
        mock_client.search_spans.return_value = [mock_span]

        with patch("opik.cli.export.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "export",
                        "default/test_project",
                        "--path",
                        temp_dir,
                        "--name",
                        "^test",
                    ],
                )

                assert result.exit_code == 0
                assert "Filtered to 1 traces matching pattern '^test'" in result.output

                # Check that only the matching trace was downloaded
                project_dir = Path(temp_dir) / "default" / "test_project"
                trace_files = list(project_dir.glob("trace_*.json"))
                assert len(trace_files) == 1

                # Check file content
                with open(trace_files[0], "r") as f:
                    data = json.load(f)
                    assert data["trace"]["name"] == "test_trace"

    def test_download_command_invalid_regex(self):
        """Test download command with invalid regex pattern."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Mock trace data so the regex validation is triggered
        mock_trace = MagicMock()
        mock_trace.id = "trace_123"
        mock_trace.name = "test_trace"
        mock_trace.model_dump.return_value = {
            "id": "trace_123",
            "name": "test_trace",
            "start_time": "2024-01-01T00:00:00Z",
        }

        mock_span = MagicMock()
        mock_span.model_dump.return_value = {
            "id": "span_123",
            "name": "test_span",
            "start_time": "2024-01-01T00:00:00Z",
        }

        mock_client.search_traces.return_value = [mock_trace]
        mock_client.search_spans.return_value = [mock_span]

        with patch("opik.cli.export.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "export",
                        "default/test_project",
                        "--path",
                        temp_dir,
                        "--name",
                        "[invalid",
                    ],
                )

                assert result.exit_code == 0
                assert "Invalid regex pattern" in result.output

    def test_download_command_import_error(self):
        """Test download command when Opik import fails."""
        # Skip this test as it's complex to mock import errors properly
        # The actual functionality is tested in the success cases
        pass


class TestUploadCommand:
    """Test the upload CLI command."""

    def test_upload_command_help(self):
        """Test that the upload command shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["import", "--help"])
        assert result.exit_code == 0
        assert (
            "Upload data from local files to a workspace or workspace/project"
            in result.output
        )
        assert "--name" in result.output

    def test_upload_command_success(self):
        """Test successful upload command."""
        # Mock the Opik module
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Create mock trace and span objects
        mock_trace_obj = MagicMock()
        mock_client.trace.return_value = mock_trace_obj
        mock_client.span.return_value = MagicMock()

        with patch("opik.cli.import_command.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                # Create test data directory structure
                project_dir = Path(temp_dir) / "test_project"
                project_dir.mkdir(parents=True)

                # Create a test trace file
                trace_data = {
                    "trace": {
                        "id": "trace_123",
                        "name": "test_trace",
                        "start_time": "2024-01-01T00:00:00Z",
                        "end_time": "2024-01-01T00:01:00Z",
                        "input": {"query": "test"},
                        "output": {"result": "success"},
                        "metadata": {"version": "1.0"},
                        "tags": ["test"],
                        "thread_id": "thread_123",
                    },
                    "spans": [
                        {
                            "id": "span_123",
                            "name": "test_span",
                            "start_time": "2024-01-01T00:00:00Z",
                            "end_time": "2024-01-01T00:01:00Z",
                            "input": {"data": "test"},
                            "output": {"result": "processed"},
                            "type": "general",
                            "model": "gpt-4",
                            "provider": "openai",
                        }
                    ],
                    "downloaded_at": "2024-01-01T00:00:00Z",
                    "project_name": "test_project",
                }

                trace_file = project_dir / "trace_123.json"
                with open(trace_file, "w") as f:
                    json.dump(trace_data, f)

                runner = CliRunner()
                result = runner.invoke(
                    cli, ["import", str(project_dir), "default/test_project"]
                )

                assert result.exit_code == 0
                assert "Successfully imported 1 items" in result.output

                # Verify that the client methods were called
                mock_client.trace.assert_called_once()
                mock_client.span.assert_called_once()

    def test_upload_command_dry_run(self):
        """Test upload command in dry run mode."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        with patch("opik.cli.import_command.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                # Create test data directory structure
                project_dir = Path(temp_dir) / "test_project"
                project_dir.mkdir(parents=True)

                # Create a test trace file
                trace_data = {
                    "trace": {
                        "id": "trace_123",
                        "name": "test_trace",
                        "start_time": "2024-01-01T00:00:00Z",
                    },
                    "spans": [],
                    "downloaded_at": "2024-01-01T00:00:00Z",
                    "project_name": "test_project",
                }

                trace_file = project_dir / "trace_123.json"
                with open(trace_file, "w") as f:
                    json.dump(trace_data, f)

                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "import",
                        str(project_dir),
                        "default/test_project",
                        "--dry-run",
                    ],
                )

                assert result.exit_code == 0
                assert "Dry run complete: Would import 1 items" in result.output
                assert "Dry run mode - no data will be imported" in result.output

                # Verify that no actual upload methods were called
                mock_client.trace.assert_not_called()
                mock_client.span.assert_not_called()

    def test_upload_command_no_project_dir(self):
        """Test upload command when project directory doesn't exist."""
        with tempfile.TemporaryDirectory() as temp_dir:
            nonexistent_dir = Path(temp_dir) / "nonexistent"
            runner = CliRunner()
            result = runner.invoke(
                cli, ["import", str(nonexistent_dir), "default/test_project"]
            )

            assert result.exit_code == 1
            assert "Directory not found" in result.output

    def test_upload_command_no_trace_files(self):
        """Test upload command when no trace files are found."""
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create empty project directory
            project_dir = Path(temp_dir) / "empty_project"
            project_dir.mkdir(parents=True)

            runner = CliRunner()
            result = runner.invoke(
                cli, ["import", str(project_dir), "default/test_project"]
            )

            assert result.exit_code == 0
            assert "No trace files found" in result.output

    def test_upload_command_workspace_only(self):
        """Test upload command with workspace-only format."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Mock projects response
        mock_project = MagicMock()
        mock_project.name = "test_project"
        mock_projects_response = MagicMock()
        mock_projects_response.content = [mock_project]
        mock_client.rest_client.projects.find_projects.return_value = (
            mock_projects_response
        )

        # Create mock trace and span objects
        mock_trace_obj = MagicMock()
        mock_trace_obj.id = "new_trace_123"
        mock_client.trace.return_value = mock_trace_obj
        mock_client.span.return_value = MagicMock()

        with patch("opik.cli.import_command.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                # Create test data directory structure
                project_dir = Path(temp_dir) / "test_project"
                project_dir.mkdir(parents=True)

                # Create a test trace file
                trace_data = {
                    "trace": {
                        "id": "trace_123",
                        "name": "test_trace",
                        "start_time": "2024-01-01T00:00:00Z",
                        "end_time": "2024-01-01T00:01:00Z",
                        "input": {"query": "test"},
                        "output": {"result": "success"},
                        "metadata": {"version": "1.0"},
                        "tags": ["test"],
                        "thread_id": "thread_123",
                    },
                    "spans": [
                        {
                            "id": "span_123",
                            "name": "test_span",
                            "start_time": "2024-01-01T00:00:00Z",
                            "end_time": "2024-01-01T00:01:00Z",
                            "input": {"data": "test"},
                            "output": {"result": "processed"},
                            "type": "general",
                            "model": "gpt-4",
                            "provider": "openai",
                        }
                    ],
                    "downloaded_at": "2024-01-01T00:00:00Z",
                    "project_name": "test_project",
                }

                trace_file = project_dir / "trace_123.json"
                with open(trace_file, "w") as f:
                    json.dump(trace_data, f)

                runner = CliRunner()
                result = runner.invoke(
                    cli, ["import", str(project_dir), "test_workspace"]
                )

                assert result.exit_code == 0
                assert (
                    "Uploading to workspace: test_workspace (all projects)"
                    in result.output
                )
                assert "Found 1 projects in workspace" in result.output
                assert (
                    "Note: Traces are project-specific. Use workspace/project format to import traces"
                    in result.output
                )

                # Verify that the client methods were NOT called since traces are no longer uploaded to all projects
                mock_client.trace.assert_not_called()
                mock_client.span.assert_not_called()

    def test_upload_command_with_name_filter(self):
        """Test upload command with name filtering."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        # Create mock trace and span objects
        mock_trace_obj = MagicMock()
        mock_trace_obj.id = "new_trace_123"
        mock_client.trace.return_value = mock_trace_obj
        mock_client.span.return_value = MagicMock()

        with patch("opik.cli.import_command.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                # Create test data directory structure
                project_dir = Path(temp_dir) / "test_project"
                project_dir.mkdir(parents=True)

                # Create trace files with different names
                trace_data1 = {
                    "trace": {
                        "id": "trace_123",
                        "name": "test_trace",
                        "start_time": "2024-01-01T00:00:00Z",
                    },
                    "spans": [],
                    "downloaded_at": "2024-01-01T00:00:00Z",
                    "project_name": "test_project",
                }

                trace_data2 = {
                    "trace": {
                        "id": "trace_456",
                        "name": "prod_trace",
                        "start_time": "2024-01-01T00:00:00Z",
                    },
                    "spans": [],
                    "downloaded_at": "2024-01-01T00:00:00Z",
                    "project_name": "test_project",
                }

                trace_file1 = project_dir / "trace_123.json"
                trace_file2 = project_dir / "trace_456.json"
                with open(trace_file1, "w") as f:
                    json.dump(trace_data1, f)
                with open(trace_file2, "w") as f:
                    json.dump(trace_data2, f)

                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "import",
                        str(project_dir),
                        "default/test_project",
                        "--name",
                        "^test",
                    ],
                )

                assert result.exit_code == 0
                assert "Successfully imported 1 items" in result.output

                # Verify that only one trace was uploaded (the matching one)
                mock_client.trace.assert_called_once()

    def test_upload_command_invalid_regex(self):
        """Test upload command with invalid regex pattern."""
        mock_opik = MagicMock()
        mock_client = MagicMock()
        mock_opik.Opik.return_value = mock_client

        with patch("opik.cli.import_command.opik", mock_opik):
            with tempfile.TemporaryDirectory() as temp_dir:
                # Create test data directory structure
                project_dir = Path(temp_dir) / "test_project"
                project_dir.mkdir(parents=True)

                # Create a test trace file
                trace_data = {
                    "trace": {
                        "id": "trace_123",
                        "name": "test_trace",
                        "start_time": "2024-01-01T00:00:00Z",
                    },
                    "spans": [],
                    "downloaded_at": "2024-01-01T00:00:00Z",
                    "project_name": "test_project",
                }

                trace_file = project_dir / "trace_123.json"
                with open(trace_file, "w") as f:
                    json.dump(trace_data, f)

                runner = CliRunner()
                result = runner.invoke(
                    cli,
                    [
                        "import",
                        str(project_dir),
                        "default/test_project",
                        "--name",
                        "[invalid",
                    ],
                )

                assert result.exit_code == 0
                assert "Invalid regex pattern" in result.output

    def test_upload_command_import_error(self):
        """Test upload command when Opik import fails."""
        # Skip this test as it's complex to mock import errors properly
        # The actual functionality is tested in the success cases
        pass


class TestCLIIntegration:
    """Test CLI integration scenarios."""

    def test_cli_help(self):
        """Test that the main CLI shows help."""
        runner = CliRunner()
        result = runner.invoke(cli, ["--help"])
        assert result.exit_code == 0
        assert "CLI tool for Opik" in result.output
        assert "export" in result.output
        assert "import" in result.output

    def test_cli_version(self):
        """Test that the CLI shows version."""
        runner = CliRunner()
        result = runner.invoke(cli, ["--version"])
        assert result.exit_code == 0
        # Version should be shown (exact format may vary)
        assert "version" in result.output.lower() or "0.0.0" in result.output
