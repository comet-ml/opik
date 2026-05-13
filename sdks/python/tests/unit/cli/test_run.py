from unittest.mock import MagicMock, patch

import pytest

from opik.cli.local_runner.preflight import (
    maybe_auto_configure,
    should_create_project,
)
from opik.rest_api.core.api_error import ApiError


class TestShouldCreateProject:
    def test__headless__returns_create_without_lookup(self):
        api = MagicMock()
        result = should_create_project(api, "any", workspace=None, headless=True)
        # Headless skips preflight, so we want to create but don't yet know
        # whether the project is actually missing — the resolver must look up.
        assert result == (True, False)
        api.projects.retrieve_project.assert_not_called()

    def test__project_exists__returns_no_create(self):
        api = MagicMock()
        api.projects.retrieve_project.return_value = MagicMock(id="proj-1")
        result = should_create_project(api, "exists", workspace="ws", headless=False)
        assert result == (False, False)

    def test__non_404_error__returns_no_create_to_let_downstream_format(self):
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=401, body={"message": "unauthorized"}
        )
        result = should_create_project(api, "x", workspace="ws", headless=False)
        assert result == (False, False)

    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__missing_and_not_tty__returns_no_create(self, mock_stdin):
        mock_stdin.isatty.return_value = False
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["not found"]}
        )
        result = should_create_project(api, "missing", workspace="ws", headless=False)
        assert result == (False, False)

    @patch("opik.cli.local_runner.preflight.click.confirm", return_value=True)
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__missing_and_tty_user_confirms__returns_create_and_known_missing(
        self, mock_stdin, mock_confirm
    ):
        mock_stdin.isatty.return_value = True
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["not found"]}
        )
        result = should_create_project(api, "missing", workspace="ws", headless=False)
        # Interactive preflight saw 404 and user confirmed — downstream can
        # skip the redundant lookup.
        assert result == (True, True)
        prompt_text = mock_confirm.call_args[0][0]
        assert "missing" in prompt_text
        assert "ws" in prompt_text

    @patch("opik.cli.local_runner.preflight.click.confirm", return_value=False)
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__missing_and_tty_user_declines__returns_no_create(
        self, mock_stdin, mock_confirm
    ):
        mock_stdin.isatty.return_value = True
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["not found"]}
        )
        result = should_create_project(api, "missing", workspace="ws", headless=False)
        assert result == (False, False)

    @patch("opik.cli.local_runner.preflight.click.confirm", return_value=True)
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__no_workspace__prompt_omits_workspace_label(
        self, mock_stdin, mock_confirm
    ):
        mock_stdin.isatty.return_value = True
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(status_code=404, body={})
        should_create_project(api, "missing", workspace=None, headless=False)
        prompt_text = mock_confirm.call_args[0][0]
        assert "in workspace" not in prompt_text


class TestMaybeAutoConfigure:
    def _patch_env(self, monkeypatch, opik_api_key=None):
        if opik_api_key is None:
            monkeypatch.delenv("OPIK_API_KEY", raising=False)
        else:
            monkeypatch.setenv("OPIK_API_KEY", opik_api_key)

    def _config_probe(self, *, config_file_exists=False, api_key=None):
        probe = MagicMock()
        probe.config_file_exists = config_file_exists
        probe.api_key = api_key
        return probe

    @patch("opik.cli.configure.run_interactive_configure")
    def test__non_interactive_flag__skips(self, mock_configure, monkeypatch):
        self._patch_env(monkeypatch)
        maybe_auto_configure(api_key_arg=None, non_interactive=True, headless=False)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    def test__headless__skips(self, mock_configure, monkeypatch):
        self._patch_env(monkeypatch)
        maybe_auto_configure(api_key_arg=None, non_interactive=False, headless=True)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    def test__api_key_arg__skips(self, mock_configure, monkeypatch):
        self._patch_env(monkeypatch)
        maybe_auto_configure(api_key_arg="abc", non_interactive=False, headless=False)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    def test__env_api_key__skips(self, mock_configure, monkeypatch):
        self._patch_env(monkeypatch, opik_api_key="from-env")
        maybe_auto_configure(api_key_arg=None, non_interactive=False, headless=False)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__stdin_not_tty__skips(self, mock_stdin, mock_configure, monkeypatch):
        self._patch_env(monkeypatch)
        mock_stdin.isatty.return_value = False
        maybe_auto_configure(api_key_arg=None, non_interactive=False, headless=False)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    @patch("opik.cli.local_runner.preflight.OpikConfig")
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__config_file_already_exists__skips(
        self, mock_stdin, mock_config_cls, mock_configure, monkeypatch
    ):
        self._patch_env(monkeypatch)
        mock_stdin.isatty.return_value = True
        mock_config_cls.return_value = self._config_probe(config_file_exists=True)
        maybe_auto_configure(api_key_arg=None, non_interactive=False, headless=False)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    @patch("opik.cli.local_runner.preflight.OpikConfig")
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__probe_resolves_api_key_from_env_chain__skips(
        self, mock_stdin, mock_config_cls, mock_configure, monkeypatch
    ):
        # The OpikConfig probe layer can pick up an API key from elsewhere
        # (e.g. .env file) even when OPIK_API_KEY isn't in os.environ. We
        # treat that as "already configured enough" and skip the prompt.
        self._patch_env(monkeypatch)
        mock_stdin.isatty.return_value = True
        mock_config_cls.return_value = self._config_probe(api_key="from-dotenv")
        maybe_auto_configure(api_key_arg=None, non_interactive=False, headless=False)
        mock_configure.assert_not_called()

    @patch("opik.cli.configure.run_interactive_configure")
    @patch("opik.cli.local_runner.preflight.OpikConfig")
    @patch("opik.cli.local_runner.preflight.sys.stdin")
    def test__no_config_and_interactive__runs_configure(
        self, mock_stdin, mock_config_cls, mock_configure, monkeypatch
    ):
        self._patch_env(monkeypatch)
        mock_stdin.isatty.return_value = True
        mock_config_cls.return_value = self._config_probe()
        maybe_auto_configure(api_key_arg=None, non_interactive=False, headless=False)
        mock_configure.assert_called_once()


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
