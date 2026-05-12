from unittest.mock import MagicMock, patch

import pytest

from opik.cli.local_runner._run import _should_create_project
from opik.rest_api.core.api_error import ApiError


class TestShouldCreateProject:
    def test__headless__returns_true_without_lookup(self):
        api = MagicMock()
        result = _should_create_project(api, "any", workspace=None, headless=True)
        assert result is True
        api.projects.retrieve_project.assert_not_called()

    def test__project_exists__returns_false(self):
        api = MagicMock()
        api.projects.retrieve_project.return_value = MagicMock(id="proj-1")
        result = _should_create_project(api, "exists", workspace="ws", headless=False)
        assert result is False

    def test__non_404_error__returns_false_to_let_downstream_format(self):
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=401, body={"message": "unauthorized"}
        )
        result = _should_create_project(api, "x", workspace="ws", headless=False)
        assert result is False

    @patch("opik.cli.local_runner._run.sys.stdin")
    def test__missing_and_not_tty__returns_false(self, mock_stdin):
        mock_stdin.isatty.return_value = False
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["not found"]}
        )
        result = _should_create_project(
            api, "missing", workspace="ws", headless=False
        )
        assert result is False

    @patch("opik.cli.local_runner._run.click.confirm", return_value=True)
    @patch("opik.cli.local_runner._run.sys.stdin")
    def test__missing_and_tty_user_confirms__returns_true(
        self, mock_stdin, mock_confirm
    ):
        mock_stdin.isatty.return_value = True
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["not found"]}
        )
        result = _should_create_project(
            api, "missing", workspace="ws", headless=False
        )
        assert result is True
        prompt_text = mock_confirm.call_args[0][0]
        assert "missing" in prompt_text
        assert "ws" in prompt_text

    @patch("opik.cli.local_runner._run.click.confirm", return_value=False)
    @patch("opik.cli.local_runner._run.sys.stdin")
    def test__missing_and_tty_user_declines__returns_false(
        self, mock_stdin, mock_confirm
    ):
        mock_stdin.isatty.return_value = True
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["not found"]}
        )
        result = _should_create_project(
            api, "missing", workspace="ws", headless=False
        )
        assert result is False

    @patch("opik.cli.local_runner._run.click.confirm", return_value=True)
    @patch("opik.cli.local_runner._run.sys.stdin")
    def test__no_workspace__prompt_omits_workspace_label(
        self, mock_stdin, mock_confirm
    ):
        mock_stdin.isatty.return_value = True
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={}
        )
        _should_create_project(api, "missing", workspace=None, headless=False)
        prompt_text = mock_confirm.call_args[0][0]
        assert "in workspace" not in prompt_text


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
