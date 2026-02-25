import configparser
from pathlib import Path
from unittest.mock import mock_open, patch

import pytest

from opik.config import OpikConfig


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
