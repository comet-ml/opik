"""Tests for agent-friendly query CLI commands."""

import json
from unittest.mock import MagicMock, patch

from click.testing import CliRunner

from opik.cli import cli


class TestQueryCli:
    def test_query_group_help(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["query", "--help"])

        assert result.exit_code == 0
        assert "Query prompts, datasets, projects" in result.output
        assert "projects" in result.output
        assert "datasets" in result.output
        assert "prompts" in result.output
        assert "traces" in result.output

    @patch("opik.cli.query.opik.Opik")
    def test_query_projects_json(self, mock_opik_cls: MagicMock) -> None:
        mock_client = MagicMock()
        mock_project = MagicMock()
        mock_project.model_dump.return_value = {"id": "p1", "name": "project-1"}
        mock_page = MagicMock()
        mock_page.content = [mock_project]
        mock_client.rest_client.projects.find_projects.return_value = mock_page
        mock_opik_cls.return_value = mock_client

        runner = CliRunner()
        result = runner.invoke(cli, ["query", "projects", "--json"])

        assert result.exit_code == 0
        payload = json.loads(result.output.strip())
        assert payload["event"] == "projects"
        assert payload["payload"]["count"] == 1

    @patch("opik.cli.query.opik.Opik")
    def test_query_prompt_json(self, mock_opik_cls: MagicMock) -> None:
        mock_client = MagicMock()
        mock_prompt = MagicMock()
        mock_prompt.model_dump.return_value = {"name": "prompt-1"}
        mock_client.get_prompt.return_value = mock_prompt
        mock_opik_cls.return_value = mock_client

        runner = CliRunner()
        result = runner.invoke(
            cli,
            ["query", "prompt", "--name", "prompt-1", "--json"],
        )

        assert result.exit_code == 0
        payload = json.loads(result.output.strip())
        assert payload["event"] == "prompt"
        assert payload["payload"]["name"] == "prompt-1"

    def test_query_completion_shell_snippet(self) -> None:
        runner = CliRunner()
        result = runner.invoke(cli, ["query", "completion", "--shell", "bash"])

        assert result.exit_code == 0
        assert "_OPIK_COMPLETE=bash_source" in result.output
