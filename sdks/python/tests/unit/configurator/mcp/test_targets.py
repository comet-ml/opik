import json
import pathlib
import subprocess
from unittest import mock

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
        server_block=SERVER_SPEC.to_block(),
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
        server_block=SERVER_SPEC.to_block(),
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
        server_block=SERVER_SPEC.to_block(),
    )

    assert result.succeeded is False
    assert "read-only file system" in result.detail
    assert "manually" in result.detail
    assert "some-key" not in result.detail


class TestHostLookup:
    def test_host_keys__match_registry_order(self):
        assert targets.HOST_KEYS == [t.key for t in targets.HOST_TARGETS]

    def test_find_target__known_key__returns_it(self):
        assert targets.find_target("codex").display_name == "Codex"

    def test_find_target__unknown_key__returns_none(self):
        assert targets.find_target("emacs") is None

    def test_detected_targets__filters_by_detector(self, monkeypatch):
        monkeypatch.setattr(
            targets,
            "HOST_TARGETS",
            [
                targets.HostTarget(
                    key="a",
                    display_name="A",
                    config_path=lambda: pathlib.Path("/dev/null"),
                    top_level_key="mcpServers",
                    is_detected=lambda: True,
                    install=lambda spec: None,
                ),
                targets.HostTarget(
                    key="b",
                    display_name="B",
                    config_path=lambda: pathlib.Path("/dev/null"),
                    top_level_key="mcpServers",
                    is_detected=lambda: False,
                    install=lambda spec: None,
                ),
            ],
        )

        assert [t.key for t in targets.detected_targets()] == ["a"]


class TestOpencodeConfigPath:
    def test_opencode_config_dir__honours_explicit_override(self, monkeypatch):
        monkeypatch.setenv("OPENCODE_CONFIG_DIR", "/custom/opencode")
        assert targets._opencode_config_dir() == pathlib.Path("/custom/opencode")

    def test_opencode_config_dir__falls_back_to_xdg(self, monkeypatch):
        monkeypatch.delenv("OPENCODE_CONFIG_DIR", raising=False)
        monkeypatch.setenv("XDG_CONFIG_HOME", "/xdg")
        assert targets._opencode_config_dir() == pathlib.Path("/xdg/opencode")

    def test_opencode_config_dir__defaults_to_dot_config(self, monkeypatch):
        monkeypatch.delenv("OPENCODE_CONFIG_DIR", raising=False)
        monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
        monkeypatch.setattr(targets, "_home", lambda: pathlib.Path("/home/user"))
        assert targets._opencode_config_dir() == pathlib.Path(
            "/home/user/.config/opencode"
        )

    def test_opencode_config_path__prefers_json_when_neither_exists(
        self, monkeypatch, tmp_path
    ):
        monkeypatch.setenv("OPENCODE_CONFIG_DIR", str(tmp_path))
        assert targets._opencode_config_path() == tmp_path / "opencode.json"

    def test_opencode_config_path__targets_existing_jsonc(self, monkeypatch, tmp_path):
        """Writing a second competing file would be worse than failing loudly."""
        monkeypatch.setenv("OPENCODE_CONFIG_DIR", str(tmp_path))
        (tmp_path / "opencode.jsonc").write_text("{}")
        assert targets._opencode_config_path() == tmp_path / "opencode.jsonc"


class TestInstallOpencode:
    def test_install_opencode__writes_opencode_shaped_block(
        self, monkeypatch, tmp_path
    ):
        monkeypatch.setenv("OPENCODE_CONFIG_DIR", str(tmp_path))

        result = targets._install_opencode(SERVER_SPEC)

        assert result.succeeded is True
        written = json.loads((tmp_path / "opencode.json").read_text())
        assert written["mcp"]["opik-mcp"]["type"] == "local"
        assert written["mcp"]["opik-mcp"]["command"] == ["/usr/bin/uvx", "opik-mcp"]
        assert written["mcp"]["opik-mcp"]["environment"]["OPIK_API_KEY"] == "some-key"

    def test_install_opencode__preserves_unrelated_keys(self, monkeypatch, tmp_path):
        monkeypatch.setenv("OPENCODE_CONFIG_DIR", str(tmp_path))
        (tmp_path / "opencode.json").write_text(
            json.dumps({"theme": "opencode", "mcp": {"other": {"type": "local"}}})
        )

        targets._install_opencode(SERVER_SPEC)

        written = json.loads((tmp_path / "opencode.json").read_text())
        assert written["theme"] == "opencode"
        assert "other" in written["mcp"]
        assert "opik-mcp" in written["mcp"]


class TestInstallCodex:
    def test_install_codex__no_cli__fails_with_manual_instructions(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: None)

        result = targets._install_codex(SERVER_SPEC)

        assert result.succeeded is False
        assert "codex` CLI was not found" in result.detail
        assert "mcp_servers.opik-mcp" in result.detail

    def test_install_codex__removes_then_adds(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")
        run_mock = mock.Mock(
            return_value=subprocess.CompletedProcess([], 0, stdout="", stderr="")
        )
        monkeypatch.setattr(targets.subprocess, "run", run_mock)

        result = targets._install_codex(SERVER_SPEC)

        assert result.succeeded is True
        # get (was it already there?) -> remove (idempotency) -> add
        get_cmd, remove_cmd, add_cmd = (
            call.args[0] for call in run_mock.call_args_list
        )
        assert get_cmd[1:] == ["mcp", "get", "opik-mcp", "--json"]
        assert remove_cmd[1:] == ["mcp", "remove", "opik-mcp"]
        assert add_cmd[1:3] == ["mcp", "add"]
        assert "opik-mcp" in add_cmd

    def test_install_codex__add_fails__reports_failure(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")

        def run(command, **kwargs):
            code = 0 if command[2] == "remove" else 1
            return subprocess.CompletedProcess(command, code, stdout="", stderr="")

        monkeypatch.setattr(targets.subprocess, "run", run)

        result = targets._install_codex(SERVER_SPEC)

        assert result.succeeded is False
        assert "exit 1" in result.detail

    def test_install_codex__does_not_leak_api_key_into_detail(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: None)
        assert "some-key" not in targets._install_codex(SERVER_SPEC).detail


class TestReadCodexBlock:
    def _codex_output(self, transport):
        return json.dumps({"name": "opik-mcp", "enabled": True, "transport": transport})

    def test_read_codex_block__no_cli__returns_none(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: None)
        assert targets._read_codex_block() is None

    def test_read_codex_block__not_registered__returns_none(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")
        monkeypatch.setattr(
            targets.subprocess,
            "run",
            lambda *a, **k: subprocess.CompletedProcess([], 1, stdout="", stderr="no"),
        )
        assert targets._read_codex_block() is None

    def test_read_codex_block__stdio__normalises_to_common_shape(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")
        output = self._codex_output(
            {
                "type": "stdio",
                "command": "/usr/bin/uvx",
                "args": ["opik-mcp"],
                "env": {"COMET_WORKSPACE": "ws"},
            }
        )
        monkeypatch.setattr(
            targets.subprocess,
            "run",
            lambda *a, **k: subprocess.CompletedProcess(
                [], 0, stdout=output, stderr=""
            ),
        )

        assert targets._read_codex_block() == {
            "type": "stdio",
            "command": "/usr/bin/uvx",
            "args": ["opik-mcp"],
            "env": {"COMET_WORKSPACE": "ws"},
        }

    def test_read_codex_block__streamable_http__reported_as_http(self, monkeypatch):
        """Codex's own transport name must not leak into the shared status view."""
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")
        output = self._codex_output(
            {"type": "streamable_http", "url": "https://www.comet.com/opik/api/v1/mcp"}
        )
        monkeypatch.setattr(
            targets.subprocess,
            "run",
            lambda *a, **k: subprocess.CompletedProcess(
                [], 0, stdout=output, stderr=""
            ),
        )

        assert targets._read_codex_block() == {
            "type": "http",
            "url": "https://www.comet.com/opik/api/v1/mcp",
        }

    def test_read_codex_block__unparseable_output__returns_none(self, monkeypatch):
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")
        monkeypatch.setattr(
            targets.subprocess,
            "run",
            lambda *a, **k: subprocess.CompletedProcess(
                [], 0, stdout="not json", stderr=""
            ),
        )
        assert targets._read_codex_block() is None

    def test_read_registered_block__delegates_to_custom_reader(self, monkeypatch):
        target = targets.HostTarget(
            key="codex",
            display_name="Codex",
            config_path=lambda: pathlib.Path("/dev/null"),
            top_level_key="mcp_servers",
            is_detected=lambda: True,
            install=lambda spec: None,
            read_block=lambda: {"type": "stdio", "command": "x"},
        )

        assert targets.read_registered_block(target) == {
            "type": "stdio",
            "command": "x",
        }


class TestInstallOutcomeVocabulary:
    """Every host reports the same thing: whether this was new or a replacement.

    "Registered" used to mean "we drove the host's CLI instead of writing the
    file" — a mechanism, mixed into a column that otherwise reported an outcome,
    and it hid new-vs-updated for exactly the hosts that use a CLI.
    """

    def test_json_host__new_entry__is_added(self, tmp_path, monkeypatch):
        monkeypatch.setattr(targets, "_cursor_config_path", lambda: tmp_path / "m.json")

        assert targets._install_cursor(SERVER_SPEC).summary == "Added"

    def test_json_host__existing_entry__is_updated(self, tmp_path, monkeypatch):
        config_path = tmp_path / "m.json"
        monkeypatch.setattr(targets, "_cursor_config_path", lambda: config_path)
        targets._install_cursor(SERVER_SPEC)

        assert targets._install_cursor(SERVER_SPEC).summary == "Updated"

    def _codex_run(self, monkeypatch, already_there):
        payload = json.dumps(
            {"transport": {"type": "stdio", "command": "uvx", "args": ["opik-mcp"]}}
        )

        def run(command, **kwargs):
            if command[1:3] == ["mcp", "get"]:
                return subprocess.CompletedProcess(
                    command, 0 if already_there else 1, stdout=payload, stderr=""
                )
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/codex")
        monkeypatch.setattr(targets.subprocess, "run", run)

    def test_codex__not_registered_yet__is_added(self, monkeypatch):
        self._codex_run(monkeypatch, already_there=False)

        assert targets._install_codex(SERVER_SPEC).summary == "Added"

    def test_codex__already_registered__is_updated(self, monkeypatch):
        """Read before the remove, which would otherwise erase the evidence."""
        self._codex_run(monkeypatch, already_there=True)

        assert targets._install_codex(SERVER_SPEC).summary == "Updated"

    def test_claude_code_via_cli__not_registered_yet__is_added(
        self, tmp_path, monkeypatch
    ):
        monkeypatch.setattr(targets, "_claude_config_path", lambda: tmp_path / "c.json")
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/claude")
        monkeypatch.setattr(
            targets.subprocess,
            "run",
            lambda *a, **k: subprocess.CompletedProcess([], 0, stdout="", stderr=""),
        )

        assert targets._install_claude_code(SERVER_SPEC).summary == "Added"

    def test_claude_code_via_cli__already_registered__is_updated(
        self, tmp_path, monkeypatch
    ):
        config_path = tmp_path / "c.json"
        config_path.write_text(
            json.dumps({"mcpServers": {"opik-mcp": {"type": "stdio"}}}),
            encoding="utf-8",
        )
        monkeypatch.setattr(targets, "_claude_config_path", lambda: config_path)
        monkeypatch.setattr(targets.shutil, "which", lambda name: "/usr/bin/claude")
        monkeypatch.setattr(
            targets.subprocess,
            "run",
            lambda *a, **k: subprocess.CompletedProcess([], 0, stdout="", stderr=""),
        )

        assert targets._install_claude_code(SERVER_SPEC).summary == "Updated"

    def test_no_host_reports_the_mechanism_as_its_outcome(self):
        """The plan block already says "via `claude mcp add`"; the result must not."""
        assert "Registered" not in pathlib.Path(targets.__file__).read_text()
