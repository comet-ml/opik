import dataclasses
import json
import os
import pathlib
import shutil
import subprocess
import sys
from typing import Callable, List

from opik.configurator.mcp import json_config
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp.spec import SERVER_NAME


@dataclasses.dataclass
class InstallResult:
    target_display_name: str
    succeeded: bool
    detail: str


@dataclasses.dataclass
class HostTarget:
    key: str
    display_name: str
    is_detected: Callable[[], bool]
    install: Callable[[mcp_spec.McpServerSpec], InstallResult]


def _home() -> pathlib.Path:
    return pathlib.Path.home()


def _claude_config_path() -> pathlib.Path:
    return _home() / ".claude.json"


def _cursor_config_path() -> pathlib.Path:
    return _home() / ".cursor" / "mcp.json"


def _vscode_user_config_path() -> pathlib.Path:
    if sys.platform == "darwin":
        base = _home() / "Library" / "Application Support"
    elif sys.platform == "win32":
        base = pathlib.Path(os.environ.get("APPDATA", _home()))
    else:
        base = pathlib.Path(os.environ.get("XDG_CONFIG_HOME", str(_home() / ".config")))
    return base / "Code" / "User" / "mcp.json"


def _manual_block_text(top_level_key: str, server_spec: mcp_spec.McpServerSpec) -> str:
    snippet = {top_level_key: {SERVER_NAME: server_spec.to_block()}}
    return json.dumps(snippet, indent=2)


def _install_via_json_file(
    config_path: pathlib.Path,
    top_level_key: str,
    display_name: str,
    server_spec: mcp_spec.McpServerSpec,
) -> InstallResult:
    try:
        was_new = json_config.merge_server_into_json_file(
            config_path=config_path,
            top_level_key=top_level_key,
            server_name=SERVER_NAME,
            server_block=server_spec.to_block(),
        )
    except json.JSONDecodeError:
        return InstallResult(
            target_display_name=display_name,
            succeeded=False,
            detail=(
                f"{config_path} exists but is not valid JSON (it may contain "
                f"comments). Add this entry manually:\n"
                f"{_manual_block_text(top_level_key, server_spec)}"
            ),
        )

    action = "Added" if was_new else "Updated"
    return InstallResult(
        target_display_name=display_name,
        succeeded=True,
        detail=f"{action} '{SERVER_NAME}' in {config_path}",
    )


def _install_claude_code(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    claude_executable = shutil.which("claude")

    if claude_executable is None:
        return _install_via_json_file(
            config_path=_claude_config_path(),
            top_level_key="mcpServers",
            display_name="Claude Code",
            server_spec=server_spec,
        )

    # `claude mcp add` errors if the server already exists, so remove any
    # previous entry first to keep the step idempotent.
    subprocess.run(
        [claude_executable, "mcp", "remove", SERVER_NAME, "--scope", "user"],
        capture_output=True,
        text=True,
    )

    command = [
        claude_executable,
        "mcp",
        "add",
        "--scope",
        "user",
    ] + server_spec.to_claude_add_args()

    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode == 0:
        return InstallResult(
            target_display_name="Claude Code",
            succeeded=True,
            detail=f"Registered '{SERVER_NAME}' via `claude mcp add` (user scope)",
        )

    return InstallResult(
        target_display_name="Claude Code",
        succeeded=False,
        detail=f"`claude mcp add` failed: {result.stderr.strip() or result.stdout.strip()}",
    )


def _install_cursor(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    return _install_via_json_file(
        config_path=_cursor_config_path(),
        top_level_key="mcpServers",
        display_name="Cursor",
        server_spec=server_spec,
    )


def _install_vscode(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    return _install_via_json_file(
        config_path=_vscode_user_config_path(),
        top_level_key="servers",
        display_name="VS Code Copilot",
        server_spec=server_spec,
    )


HOST_TARGETS: List[HostTarget] = [
    HostTarget(
        key="claude-code",
        display_name="Claude Code",
        is_detected=lambda: shutil.which("claude") is not None
        or _claude_config_path().exists(),
        install=_install_claude_code,
    ),
    HostTarget(
        key="cursor",
        display_name="Cursor",
        is_detected=lambda: (_home() / ".cursor").exists(),
        install=_install_cursor,
    ),
    HostTarget(
        key="vscode",
        display_name="VS Code Copilot",
        is_detected=lambda: _vscode_user_config_path().parent.exists(),
        install=_install_vscode,
    ),
]
