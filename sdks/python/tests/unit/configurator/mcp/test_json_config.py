import json

import pytest

from opik.configurator.mcp import json_config

SERVER_BLOCK = {
    "type": "stdio",
    "command": "/usr/bin/uvx",
    "args": ["opik-mcp"],
    "env": {"OPIK_API_KEY": "some-key"},
}


def test_merge_server_into_json_file__new_file__creates_with_entry(tmp_path):
    config_path = tmp_path / "nested" / "mcp.json"

    was_new = json_config.merge_server_into_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        server_name="opik-mcp",
        server_block=SERVER_BLOCK,
    )

    assert was_new is True
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert written == {"mcpServers": {"opik-mcp": SERVER_BLOCK}}


def test_merge_server_into_json_file__existing_servers__preserved(tmp_path):
    config_path = tmp_path / "mcp.json"
    config_path.write_text(
        json.dumps(
            {
                "someOtherTopLevelKey": {"keep": "me"},
                "mcpServers": {"other-server": {"command": "foo"}},
            }
        ),
        encoding="utf-8",
    )

    was_new = json_config.merge_server_into_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        server_name="opik-mcp",
        server_block=SERVER_BLOCK,
    )

    assert was_new is True
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert written["someOtherTopLevelKey"] == {"keep": "me"}
    assert written["mcpServers"]["other-server"] == {"command": "foo"}
    assert written["mcpServers"]["opik-mcp"] == SERVER_BLOCK


def test_merge_server_into_json_file__rerun__updates_in_place(tmp_path):
    config_path = tmp_path / "mcp.json"
    json_config.merge_server_into_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        server_name="opik-mcp",
        server_block={"command": "old"},
    )

    was_new = json_config.merge_server_into_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        server_name="opik-mcp",
        server_block=SERVER_BLOCK,
    )

    assert was_new is False
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert list(written["mcpServers"].keys()) == ["opik-mcp"]
    assert written["mcpServers"]["opik-mcp"] == SERVER_BLOCK


def test_merge_server_into_json_file__invalid_json__raises(tmp_path):
    config_path = tmp_path / "mcp.json"
    config_path.write_text("{ // a JSONC comment\n}", encoding="utf-8")

    with pytest.raises(json.JSONDecodeError):
        json_config.merge_server_into_json_file(
            config_path=config_path,
            top_level_key="servers",
            server_name="opik-mcp",
            server_block=SERVER_BLOCK,
        )


def test_merge_server_into_json_file__non_object_root__raises_value_error(tmp_path):
    config_path = tmp_path / "mcp.json"
    config_path.write_text('["not", "an", "object"]', encoding="utf-8")

    with pytest.raises(ValueError):
        json_config.merge_server_into_json_file(
            config_path=config_path,
            top_level_key="mcpServers",
            server_name="opik-mcp",
            server_block=SERVER_BLOCK,
        )


def test_merge_server_into_json_file__empty_existing_file__treated_as_empty(tmp_path):
    config_path = tmp_path / "mcp.json"
    config_path.write_text("", encoding="utf-8")

    was_new = json_config.merge_server_into_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        server_name="opik-mcp",
        server_block=SERVER_BLOCK,
    )

    assert was_new is True
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert written["mcpServers"]["opik-mcp"] == SERVER_BLOCK
