import dataclasses
import json
import os
import pathlib
import shutil
import subprocess
import sys
from typing import Any, Callable, Dict, Final, List, Optional

from opik.configurator.mcp import json_config
from opik.configurator.mcp import spec as mcp_spec
from opik.configurator.mcp.spec import SERVER_NAME


@dataclasses.dataclass
class InstallResult:
    target_display_name: str
    succeeded: bool
    detail: str
    # A few words for a display that already showed the location ("Added",
    # "Updated"). Falls back to `detail`, which spells the path out in full and is
    # what a log line or a failure needs.
    summary: Optional[str] = None


@dataclasses.dataclass
class HostTarget:
    key: str
    display_name: str
    config_path: Callable[[], pathlib.Path]
    top_level_key: str
    is_detected: Callable[[], bool]
    install: Callable[[mcp_spec.McpServerSpec], InstallResult]
    # Hosts that do not keep their MCP registration in a plain JSON object under
    # ``top_level_key`` (Codex uses TOML) supply their own reader; see
    # ``read_registered_block``.
    read_block: Optional[Callable[[], Optional[Dict[str, Any]]]] = None


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


def _codex_config_path() -> pathlib.Path:
    return _home() / ".codex" / "config.toml"


def _opencode_config_dir() -> pathlib.Path:
    override = os.environ.get("OPENCODE_CONFIG_DIR")
    if override:
        return pathlib.Path(override)
    xdg_config_home = os.environ.get("XDG_CONFIG_HOME")
    if xdg_config_home:
        return pathlib.Path(xdg_config_home) / "opencode"
    return _home() / ".config" / "opencode"


def _opencode_config_path() -> pathlib.Path:
    """The opencode config file to write.

    opencode accepts ``opencode.json`` or ``opencode.jsonc``. We prefer the
    strict-JSON name, but target an existing ``.jsonc`` when that is the only one
    present — writing a second competing file would be worse than failing with
    manual instructions if its comments defeat the JSON parser.
    """
    config_dir = _opencode_config_dir()
    json_path = config_dir / "opencode.json"
    if json_path.exists():
        return json_path
    jsonc_path = config_dir / "opencode.jsonc"
    if jsonc_path.exists():
        return jsonc_path
    return json_path


def _first_line(output: Optional[str]) -> str:
    """The first meaningful line of a captured stream, for an error detail."""
    for line in (output or "").splitlines():
        if line.strip():
            return line.strip()
    return ""


def _manual_block_text(top_level_key: str, block: Dict[str, Any]) -> str:
    snippet = {top_level_key: {SERVER_NAME: mcp_spec.redact_block_for_display(block)}}
    return json.dumps(snippet, indent=2)


def _install_via_json_file(
    config_path: pathlib.Path,
    top_level_key: str,
    display_name: str,
    server_block: Dict[str, Any],
) -> InstallResult:
    try:
        was_new = json_config.merge_server_into_json_file(
            config_path=config_path,
            top_level_key=top_level_key,
            server_name=SERVER_NAME,
            server_block=server_block,
        )
    except ValueError:  # JSONDecodeError, or non-object JSON root (see json_config)
        return InstallResult(
            target_display_name=display_name,
            succeeded=False,
            detail=(
                f"{config_path} exists but is not a valid JSON object (it may "
                f"contain comments or a non-object value). Add this entry "
                f"manually:\n{_manual_block_text(top_level_key, server_block)}"
            ),
        )
    except OSError as error:
        return InstallResult(
            target_display_name=display_name,
            succeeded=False,
            detail=(
                f"Could not write {config_path}: {error}. Add this entry "
                f"manually:\n{_manual_block_text(top_level_key, server_block)}"
            ),
        )

    action = "Added" if was_new else "Updated"
    return InstallResult(
        target_display_name=display_name,
        succeeded=True,
        detail=f"{action} '{SERVER_NAME}' in {config_path}",
        summary=action,
    )


#: How long to let a client's own CLI run before giving up on it. Generous: these
#: shell out to Node tools that may cold-start, but bounded, because the whole
#: point is that `opik configure` must not hang forever.
CLIENT_CLI_TIMEOUT_SECONDS: Final[int] = 60


class _CliUnavailable(Exception):
    """A client's CLI could not be run to completion."""


def _run_client_cli(command: List[str], label: str) -> "subprocess.CompletedProcess":
    """Run a client's own CLI, or raise :class:`_CliUnavailable`.

    ``shutil.which`` finding the binary is not evidence it will exec — it only
    checks the executable bit. ``claude`` and ``codex`` are Node shims, so the
    common real-world break is node moving out from under them: an nvm version
    deleted, node upgraded, a part-removed npm install. The shim still passes
    ``which`` and then raises ``FileNotFoundError`` at exec, which without this
    was an unhandled traceback out of `opik configure`.

    The errno text is misleading in that case — the missing file is node, not the
    shim it names — which is why the message here explains it rather than passing
    the exception's own wording through.

    ``stdin`` is closed and a timeout set for the other half of the problem: these
    inherit the terminal otherwise, so a client CLI that decides to prompt (a
    login, a migration confirmation) waits on input nobody is going to type.
    """
    try:
        return subprocess.run(
            command,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=CLIENT_CLI_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise _CliUnavailable(
            f"`{label}` did not finish within {CLIENT_CLI_TIMEOUT_SECONDS}s and was "
            "stopped. It may be waiting for input, or asking you to log in — try "
            f"running `{label}` yourself to see."
        )
    except OSError as error:
        raise _CliUnavailable(
            f"`{label}` could not be started ({error.strerror or error}). The "
            f"command exists at {command[0]} but failed to run, which usually means "
            "the runtime behind it (node, for these tools) was moved or upgraded. "
            "Reinstalling the tool normally fixes it."
        )


def _install_claude_code(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    claude_executable = shutil.which("claude")

    if claude_executable is None:
        # The file path works out new-vs-existing itself.
        return _install_via_json_file(
            config_path=_claude_config_path(),
            top_level_key="mcpServers",
            display_name="Claude Code",
            server_block=server_spec.to_block(),
        )

    # Read before writing, so the result can say whether this replaced an existing
    # registration. `claude mcp add` cannot tell us — we remove first to keep the
    # step idempotent, which erases the evidence.
    was_registered = (
        _read_block_from_json_file(_claude_config_path(), "mcpServers") is not None
    )

    # `claude mcp add` errors if the server already exists, so remove any
    # previous entry first to keep the step idempotent.
    command = [
        claude_executable,
        "mcp",
        "add",
        "--scope",
        "user",
    ] + server_spec.to_claude_add_args()

    # Captured, not streamed: we report the outcome ourselves, and its own
    # "Added HTTP MCP server … / File modified: …" lines landed unstyled in the
    # middle of the wizard. On failure the captured text goes into the detail.
    try:
        _run_client_cli(
            [claude_executable, "mcp", "remove", SERVER_NAME, "--scope", "user"],
            label="claude mcp remove",
        )
        result = _run_client_cli(command, label="claude mcp add")
    except _CliUnavailable as error:
        return InstallResult(
            target_display_name="Claude Code", succeeded=False, detail=str(error)
        )
    if result.returncode == 0:
        return InstallResult(
            target_display_name="Claude Code",
            succeeded=True,
            detail=(
                f"{'Updated' if was_registered else 'Added'} '{SERVER_NAME}' via "
                f"`claude mcp add` (user scope)"
            ),
            summary="Updated" if was_registered else "Added",
        )

    return InstallResult(
        target_display_name="Claude Code",
        succeeded=False,
        detail=(
            f"`claude mcp add` failed (exit {result.returncode}): "
            f"{_first_line(result.stderr) or _first_line(result.stdout) or 'no output'}"
        ),
    )


def _install_cursor(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    return _install_via_json_file(
        config_path=_cursor_config_path(),
        top_level_key="mcpServers",
        display_name="Cursor",
        server_block=server_spec.to_block(),
    )


def _install_vscode(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    return _install_via_json_file(
        config_path=_vscode_user_config_path(),
        top_level_key="servers",
        display_name="VS Code Copilot",
        server_block=server_spec.to_block(),
    )


def _install_opencode(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    return _install_via_json_file(
        config_path=_opencode_config_path(),
        top_level_key="mcp",
        display_name="opencode",
        server_block=server_spec.to_opencode_block(),
    )


def _codex_manual_instructions() -> str:
    """What to tell the user when we cannot drive the ``codex`` CLI.

    Codex stores servers in TOML, which we deliberately do not hand-edit: merging
    into someone else's TOML without a writer risks losing their comments and
    formatting. So when the CLI is unavailable we hand the work back rather than
    guessing.
    """
    return (
        f"the `codex` CLI was not found on your PATH, and {_codex_config_path()} is "
        "TOML, which this installer does not edit directly. Install the Codex CLI "
        "and re-run `opik mcp configure --ai-client codex`, or add an "
        f"`[mcp_servers.{SERVER_NAME}]` table to that file by hand."
    )


def _install_codex(server_spec: mcp_spec.McpServerSpec) -> InstallResult:
    codex_executable = shutil.which("codex")

    if codex_executable is None:
        return InstallResult(
            target_display_name="Codex",
            succeeded=False,
            detail=f"Could not register '{SERVER_NAME}': {_codex_manual_instructions()}",
        )

    # Read before the remove below erases the evidence, so the result can say
    # whether this replaced an existing registration.
    was_registered = _read_codex_block() is not None

    # `codex mcp add` refuses when the server already exists, so drop any previous
    # entry first to keep re-runs idempotent — same shape as the Claude Code path.
    command = [codex_executable, "mcp", "add"] + server_spec.to_codex_add_args()

    try:
        _run_client_cli(
            [codex_executable, "mcp", "remove", SERVER_NAME],
            label="codex mcp remove",
        )
        result = _run_client_cli(command, label="codex mcp add")
    except _CliUnavailable as error:
        return InstallResult(
            target_display_name="Codex", succeeded=False, detail=str(error)
        )
    if result.returncode == 0:
        return InstallResult(
            target_display_name="Codex",
            succeeded=True,
            detail=(
                f"{'Updated' if was_registered else 'Added'} '{SERVER_NAME}' via "
                f"`codex mcp add`"
            ),
            summary="Updated" if was_registered else "Added",
        )

    return InstallResult(
        target_display_name="Codex",
        succeeded=False,
        detail=(
            f"`codex mcp add` failed (exit {result.returncode}): "
            f"{_first_line(result.stderr) or _first_line(result.stdout) or 'no output'}"
        ),
    )


def _read_codex_block() -> Optional[Dict[str, Any]]:
    """Read Codex's registration through its own CLI and normalise the shape.

    ``codex mcp get <name> --json`` reports the transport under a ``transport``
    key using Codex's own vocabulary (``streamable_http``). We translate it into
    the same block shape every other host records, so ``opik mcp status`` needs no
    per-host special casing. Reading via the CLI also avoids parsing TOML, which
    has no standard-library reader on every Python version the SDK supports.
    """
    codex_executable = shutil.which("codex")
    if codex_executable is None:
        return None

    try:
        result = _run_client_cli(
            [codex_executable, "mcp", "get", SERVER_NAME, "--json"],
            label="codex mcp get",
        )
    except _CliUnavailable:
        # Only used to enrich the result with "was it already registered"; a
        # client whose CLI will not run is reported by the write path instead.
        return None

    if result.returncode != 0:
        return None

    try:
        payload = json.loads(result.stdout)
    except ValueError:
        return None

    transport = payload.get("transport") if isinstance(payload, dict) else None
    if not isinstance(transport, dict):
        return None

    if transport.get("type") == "streamable_http":
        return {"type": "http", "url": str(transport.get("url", ""))}

    return {
        "type": "stdio",
        "command": transport.get("command"),
        "args": transport.get("args") or [],
        "env": transport.get("env") or {},
    }


def read_registered_block(target: "HostTarget") -> Optional[Dict[str, Any]]:
    """Return the ``opik-mcp`` block recorded in a host's config, or ``None``.

    The read-only counterpart to the install path, used by ``opik mcp status`` to
    report what each host currently points at. A missing, unreadable, or malformed
    config is treated as "nothing registered" rather than an error.

    For Claude Code this reads ``~/.claude.json`` directly, which is where
    ``claude mcp add --scope user`` records the server, so it works whether or not
    the ``claude`` CLI is installed. Hosts whose config is not a JSON object
    (Codex, which uses TOML) provide their own ``read_block``.
    """
    if target.read_block is not None:
        return target.read_block()

    return _read_block_from_json_file(target.config_path(), target.top_level_key)


def _read_block_from_json_file(
    config_path: pathlib.Path, top_level_key: str
) -> Optional[Dict[str, Any]]:
    try:
        if not config_path.exists() or config_path.stat().st_size == 0:
            return None
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None

    if not isinstance(data, dict):
        return None

    servers = data.get(top_level_key)
    if not isinstance(servers, dict):
        return None

    block = servers.get(SERVER_NAME)
    return block if isinstance(block, dict) else None


HOST_TARGETS: List[HostTarget] = [
    HostTarget(
        key="claude-code",
        display_name="Claude Code",
        config_path=_claude_config_path,
        top_level_key="mcpServers",
        is_detected=lambda: shutil.which("claude") is not None
        or _claude_config_path().exists(),
        install=_install_claude_code,
    ),
    HostTarget(
        key="cursor",
        display_name="Cursor",
        config_path=_cursor_config_path,
        top_level_key="mcpServers",
        is_detected=lambda: (_home() / ".cursor").exists(),
        install=_install_cursor,
    ),
    HostTarget(
        key="vscode",
        display_name="VS Code Copilot",
        config_path=_vscode_user_config_path,
        top_level_key="servers",
        is_detected=lambda: _vscode_user_config_path().parent.exists(),
        install=_install_vscode,
    ),
    HostTarget(
        key="codex",
        display_name="Codex",
        config_path=_codex_config_path,
        # Unused: Codex config is TOML, so reads go through `read_block` instead.
        top_level_key="mcp_servers",
        is_detected=lambda: shutil.which("codex") is not None
        or _codex_config_path().exists(),
        install=_install_codex,
        read_block=_read_codex_block,
    ),
    HostTarget(
        key="opencode",
        display_name="opencode",
        config_path=_opencode_config_path,
        top_level_key="mcp",
        is_detected=lambda: shutil.which("opencode") is not None
        or _opencode_config_dir().exists(),
        install=_install_opencode,
    ),
]

HOST_KEYS: List[str] = [target.key for target in HOST_TARGETS]


def find_target(key: str) -> Optional[HostTarget]:
    """Look a host up by its CLI key (the values accepted by ``--ai-client``)."""
    for target in HOST_TARGETS:
        if target.key == key:
            return target
    return None


def detected_targets() -> List[HostTarget]:
    return [target for target in HOST_TARGETS if target.is_detected()]
