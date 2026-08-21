import pathlib

from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.skills import roots


def _target(key, display_name, detected):
    return mcp_targets.HostTarget(
        key=key,
        display_name=display_name,
        config_path=lambda: pathlib.Path("/dev/null"),
        top_level_key="mcpServers",
        is_detected=lambda: detected,
        install=lambda spec: None,
    )


def test_supported_host_keys__are_all_real_opik_hosts():
    """A supported key that is not an Opik host would offer a dead `--host` value."""
    for host_key in roots.SUPPORTED_HOST_KEYS:
        assert host_key in mcp_targets.HOST_KEYS


def test_shared_skills_dir__is_the_cross_agent_location(monkeypatch, tmp_path):
    monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
    assert roots.shared_skills_dir() == tmp_path / ".agents" / "skills"


def test_claude_code_skills_dir__is_its_own_directory(monkeypatch, tmp_path):
    monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
    assert roots.claude_code_skills_dir() == tmp_path / ".claude" / "skills"


def test_reads_shared_dir__true_for_every_host_except_claude_code():
    """Codex, opencode, Cursor and Copilot read ~/.agents/skills natively."""
    for host_key in roots.SUPPORTED_HOST_KEYS:
        expected = host_key != "claude-code"
        assert roots.reads_shared_dir(host_key) is expected


def test_needs_link__only_claude_code():
    assert roots.needs_link("claude-code") is True
    assert roots.needs_link("codex") is False


def test_link_dir__claude_code_gets_a_directory(monkeypatch, tmp_path):
    monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
    assert roots.link_dir("claude-code") == tmp_path / ".claude" / "skills"


def test_link_dir__shared_dir_hosts_need_none():
    assert roots.link_dir("codex") is None
    assert roots.link_dir("opencode") is None


def test_reads_shared_dir__unsupported_host__is_false():
    assert roots.reads_shared_dir("emacs") is False


def test_detected_host_keys__only_detected_and_supported(monkeypatch):
    monkeypatch.setattr(
        mcp_targets,
        "HOST_TARGETS",
        [
            _target("codex", "Codex", True),
            _target("cursor", "Cursor", False),
            _target("emacs", "Emacs", True),  # detected but unsupported
        ],
    )

    assert roots.detected_host_keys() == ["codex"]


def test_display_names__uses_host_display_names(monkeypatch):
    monkeypatch.setattr(
        mcp_targets, "HOST_TARGETS", [_target("vscode", "VS Code Copilot", True)]
    )

    assert roots.display_names(["vscode"]) == ["VS Code Copilot"]


def test_display_names__unknown_key__falls_back_to_the_key():
    assert roots.display_names(["emacs"]) == ["emacs"]
