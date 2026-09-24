import configparser
from pathlib import Path
from unittest.mock import mock_open, patch

import pytest

from opik.config import CONFIG_FILE_PATH_DEFAULT, OpikConfig


@pytest.fixture(autouse=True)
def mock_env_and_file(monkeypatch):
    monkeypatch.delenv("OPIK_API_KEY", raising=False)
    monkeypatch.delenv("OPIK_WORKSPACE", raising=False)
    monkeypatch.delenv("OPIK_URL_OVERRIDE", raising=False)

    with patch("builtins.open", side_effect=FileNotFoundError):
        yield


@patch("builtins.open", new_callable=mock_open)
@patch("pathlib.Path.expanduser", return_value=Path("/fake/path/config.ini"))
def test_save_to_file_content(mock_expanduser, mock_open_file):
    config = OpikConfig(
        api_key="test_api_key",
        url_override="http://test-url",
        workspace="test_workspace",
    )

    config.save_to_file()

    # Assert the file was opened with the correct path and mode
    mock_open_file.assert_called_once()
    assert Path(mock_open_file.call_args_list[0].args[0]) == Path(
        "/fake/path/config.ini"
    )
    assert mock_open_file.call_args_list[0].kwargs == {
        "encoding": "utf-8",
        "mode": "w+",
    }

    # Get the file handle to check what was written
    handle = mock_open_file()

    # Collect all the written content
    written_content = "".join(call.args[0] for call in handle.write.call_args_list)

    # Create a config parser to parse the written content
    parsed_config = configparser.ConfigParser()
    parsed_config.read_string(written_content)

    # Assert the correct content was written to the file
    assert parsed_config["opik"]["url_override"] == "http://test-url"
    assert parsed_config["opik"]["workspace"] == "test_workspace"
    assert parsed_config["opik"]["api_key"] == "test_api_key"


@patch("builtins.open", new_callable=mock_open)
@patch("pathlib.Path.expanduser", return_value=Path("/fake/path/config.ini"))
def test_save_to_file_without_api_key(mock_expanduser, mock_open_file):
    config = OpikConfig(url_override="http://test-url", workspace="test_workspace")

    config.save_to_file()

    # Get the file handle to check what was written
    handle = mock_open_file()

    # Collect all the written content
    written_content = "".join(call.args[0] for call in handle.write.call_args_list)

    # Create a config parser to parse the written content
    parsed_config = configparser.ConfigParser()
    parsed_config.read_string(written_content)

    # Assert the correct content was written to the file, without the API key
    assert parsed_config["opik"]["url_override"] == "http://test-url"
    assert parsed_config["opik"]["workspace"] == "test_workspace"
    assert "api_key" not in parsed_config["opik"]


def test_default_llm_loaded_from_env(monkeypatch):
    monkeypatch.setenv("OPIK_DEFAULT_LLM", "gpt-4.1-mini")

    config = OpikConfig()

    assert config.default_llm == "gpt-4.1-mini"


def test_environment_loaded_from_env(monkeypatch):
    monkeypatch.setenv("OPIK_ENVIRONMENT", "production")

    config = OpikConfig()

    assert config.environment == "production"


def test_environment_defaults_to_none():
    config = OpikConfig()

    assert config.environment is None


def test_runner_poll_interval_defaults_to_half_second():
    config = OpikConfig()

    assert config.runner_poll_interval == 0.5


def test_runner_poll_interval_loaded_from_env(monkeypatch):
    monkeypatch.setenv("OPIK_RUNNER_POLL_INTERVAL", "2.5")

    config = OpikConfig()

    assert config.runner_poll_interval == 2.5


@patch("builtins.open", new_callable=mock_open)
@patch("pathlib.Path.expanduser", return_value=Path("/fake/path/config.ini"))
def test_save_to_file_does_not_persist_environment(mock_expanduser, mock_open_file):
    config = OpikConfig(
        url_override="http://test-url",
        workspace="test_workspace",
        environment="production",
    )

    config.save_to_file()

    handle = mock_open_file()
    written_content = "".join(call.args[0] for call in handle.write.call_args_list)

    parsed_config = configparser.ConfigParser()
    parsed_config.read_string(written_content)

    assert "environment" not in parsed_config["opik"]


@pytest.mark.parametrize("blank_path", ["", "   ", "\t"])
def test_blank_opik_config_path_falls_back_to_default(monkeypatch, blank_path):
    """
    ``os.getenv(..., default)`` only applies when the variable is missing.
    A blank assignment (Windows ``set OPIK_CONFIG_PATH=``, a `.env` line
    with no value) would otherwise resolve to the current directory and
    ignore ``~/.opik.config``.
    """
    monkeypatch.setenv("OPIK_CONFIG_PATH", blank_path)

    config = OpikConfig()

    assert config.config_file_fullpath == Path(CONFIG_FILE_PATH_DEFAULT).expanduser()


def test_unset_opik_config_path_uses_default(monkeypatch):
    monkeypatch.delenv("OPIK_CONFIG_PATH", raising=False)

    config = OpikConfig()

    assert config.config_file_fullpath == Path(CONFIG_FILE_PATH_DEFAULT).expanduser()


def test_custom_opik_config_path_is_honored(monkeypatch, tmp_path):
    custom = tmp_path / "custom.opik.config"
    monkeypatch.setenv("OPIK_CONFIG_PATH", str(custom))

    config = OpikConfig()

    assert config.config_file_fullpath == custom


def test_padded_opik_config_path_is_honored(monkeypatch, tmp_path):
    custom = tmp_path / "custom.opik.config"
    monkeypatch.setenv("OPIK_CONFIG_PATH", f"  {custom}  ")

    config = OpikConfig()

    assert config.config_file_fullpath == custom
