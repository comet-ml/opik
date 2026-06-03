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
        if command[2] == "remove":
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
        return subprocess.CompletedProcess(command, 1, stdout="", stderr="boom")

    monkeypatch.setattr(targets.subprocess, "run", fake_run)

    result = targets._install_claude_code(SERVER_SPEC)

    assert result.succeeded is False
    assert "boom" in result.detail


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
