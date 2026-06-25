import json
import pathlib
import subprocess

from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp import targets

SERVER_SPEC = mcp_spec.StdioServerSpec(
    command="/usr/bin/uvx",
    args=["opik-mcp"],
    env={"OPIK_API_KEY": "some-key", "COMET_WORKSPACE": "ws"},
)


def test_config_paths__use_home_directory(monkeypatch):
    monkeypatch.setattr(targets, "_home", lambda: pathlib.Path("/home/user"))

    assert targets._claude_config_path() == pathlib.Path("/home/user/.claude.json")
    assert targets._cursor_config_path() == pathlib.Path("/home/user/.cursor/mcp.json")


def test_vscode_user_config_path__per_platform(monkeypatch):
    monkeypatch.setattr(targets, "_home", lambda: pathlib.Path("/home/user"))

    monkeypatch.setattr(targets.sys, "platform", "darwin")
    assert targets._vscode_user_config_path() == pathlib.Path(
        "/home/user/Library/Application Support/Code/User/mcp.json"
    )

    monkeypatch.setattr(targets.sys, "platform", "win32")
    monkeypatch.setenv("APPDATA", "/appdata")
    assert targets._vscode_user_config_path() == pathlib.Path(
        "/appdata/Code/User/mcp.json"
    )

    monkeypatch.setattr(targets.sys, "platform", "linux")
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    assert targets._vscode_user_config_path() == pathlib.Path(
        "/home/user/.config/Code/User/mcp.json"
    )


def test_install_vscode__uses_servers_top_level_key(tmp_path, monkeypatch):
    config_path = tmp_path / "mcp.json"
    monkeypatch.setattr(targets, "_vscode_user_config_path", lambda: config_path)

    result = targets._install_vscode(SERVER_SPEC)

    assert result.succeeded is True
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert "servers" in written
    assert "mcpServers" not in written
    assert written["servers"]["opik-mcp"]["command"] == "/usr/bin/uvx"


def test_install_cursor__uses_mcp_servers_top_level_key(tmp_path, monkeypatch):
    config_path = tmp_path / "mcp.json"
    monkeypatch.setattr(targets, "_cursor_config_path", lambda: config_path)

    result = targets._install_cursor(SERVER_SPEC)

    assert result.succeeded is True
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert written["mcpServers"]["opik-mcp"]["env"]["OPIK_API_KEY"] == "some-key"


def test_install_claude_code__no_cli__falls_back_to_json_file(tmp_path, monkeypatch):
    config_path = tmp_path / ".claude.json"
    monkeypatch.setattr(targets.shutil, "which", lambda name: None)
    monkeypatch.setattr(targets, "_claude_config_path", lambda: config_path)

    result = targets._install_claude_code(SERVER_SPEC)

    assert result.succeeded is True
    written = json.loads(config_path.read_text(encoding="utf-8"))
    assert written["mcpServers"]["opik-mcp"]["args"] == ["opik-mcp"]


def test_install_claude_code__with_cli__runs_remove_then_add(monkeypatch):
    monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/claude")
    recorded_commands = []

    def fake_run(command, **kwargs):
        recorded_commands.append(command)
        return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

    monkeypatch.setattr(targets.subprocess, "run", fake_run)

    result = targets._install_claude_code(SERVER_SPEC)

    assert result.succeeded is True
    assert recorded_commands[0][:3] == ["/usr/bin/claude", "mcp", "remove"]

    add_command = recorded_commands[1]
    assert add_command[:3] == ["/usr/bin/claude", "mcp", "add"]
    assert "--env" in add_command
    assert "OPIK_API_KEY=some-key" in add_command
    separator_index = add_command.index("--")
    assert add_command[separator_index + 1 :] == ["/usr/bin/uvx", "opik-mcp"]


def test_install_claude_code__cli_failure__reports_failure(monkeypatch):
    monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/claude")

    def fake_run(command, **kwargs):
        return subprocess.CompletedProcess(command, 0 if command[2] == "remove" else 1)

    monkeypatch.setattr(targets.subprocess, "run", fake_run)

    result = targets._install_claude_code(SERVER_SPEC)

    assert result.succeeded is False
    assert "`claude mcp add` failed" in result.detail
    assert "exit 1" in result.detail


def test_install_via_json_file__invalid_json__returns_manual_instructions(
    tmp_path,
):
    config_path = tmp_path / "mcp.json"
    config_path.write_text("{ // jsonc\n}", encoding="utf-8")

    result = targets._install_via_json_file(
        config_path=config_path,
        top_level_key="servers",
        display_name="VS Code Copilot",
        server_spec=SERVER_SPEC,
    )

    assert result.succeeded is False
    assert "manually" in result.detail
    assert "opik-mcp" in result.detail
    # the API key must not leak into the (logged) manual-setup instructions
    assert "some-key" not in result.detail
    assert "***REDACTED***" in result.detail


def test_install_via_json_file__non_object_root__returns_manual_instructions(tmp_path):
    config_path = tmp_path / "mcp.json"
    config_path.write_text('"a bare string"', encoding="utf-8")

    result = targets._install_via_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        display_name="Cursor",
        server_spec=SERVER_SPEC,
    )

    assert result.succeeded is False
    assert "manually" in result.detail
    assert "some-key" not in result.detail


def _read_target(tmp_path, top_level_key="mcpServers"):
    return targets.HostTarget(
        key="probe",
        display_name="Probe",
        config_path=lambda: tmp_path / "config.json",
        top_level_key=top_level_key,
        is_detected=lambda: True,
        install=lambda spec: None,
    )


def test_read_registered_block__returns_recorded_block(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(
        json.dumps({"mcpServers": {"opik-mcp": {"type": "http", "url": "https://x"}}}),
        encoding="utf-8",
    )

    block = targets.read_registered_block(_read_target(tmp_path))

    assert block == {"type": "http", "url": "https://x"}


def test_read_registered_block__missing_file__returns_none(tmp_path):
    assert targets.read_registered_block(_read_target(tmp_path)) is None


def test_read_registered_block__no_entry__returns_none(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(
        json.dumps({"mcpServers": {"other-server": {}}}), encoding="utf-8"
    )

    assert targets.read_registered_block(_read_target(tmp_path)) is None


def test_read_registered_block__malformed_json__returns_none(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text("{ not json", encoding="utf-8")

    assert targets.read_registered_block(_read_target(tmp_path)) is None


def test_read_registered_block__honors_top_level_key(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(
        json.dumps({"servers": {"opik-mcp": {"type": "http", "url": "https://y"}}}),
        encoding="utf-8",
    )

    # Looking under "mcpServers" finds nothing; under "servers" finds the block.
    assert targets.read_registered_block(_read_target(tmp_path)) is None
    assert targets.read_registered_block(
        _read_target(tmp_path, top_level_key="servers")
    ) == {"type": "http", "url": "https://y"}


def test_install_via_json_file__os_error__returns_failed_result(monkeypatch, tmp_path):
    config_path = tmp_path / "mcp.json"

    def boom(**kwargs):
        raise PermissionError("read-only file system")

    monkeypatch.setattr(targets.json_config, "merge_server_into_json_file", boom)

    result = targets._install_via_json_file(
        config_path=config_path,
        top_level_key="mcpServers",
        display_name="Cursor",
        server_spec=SERVER_SPEC,
    )

    assert result.succeeded is False
    assert "read-only file system" in result.detail
    assert "manually" in result.detail
    assert "some-key" not in result.detail
