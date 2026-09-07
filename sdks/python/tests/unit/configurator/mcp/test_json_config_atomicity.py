"""The user's own editor config must survive a failed write.

`~/.claude.json` is the whole of Claude Code's user state, not just an MCP block.
The write used to truncate before writing, so a failure part-way left it
unparseable — and the caller catches OSError and prints a tidy message, so
nothing revealed the damage.
"""

import json
import os
import stat

import pytest

from opik.configurator.mcp import json_config


def _merge(path):
    return json_config.merge_server_into_json_file(
        path, "mcpServers", "opik", {"command": "uvx", "args": ["opik-mcp"]}
    )


@pytest.fixture
def disk_full(monkeypatch):
    """Fail where a real full disk does: flushing the bytes, before the rename.

    Targeted at `os.fsync` rather than the write call itself — patching
    `TextIOWrapper.write` globally also breaks pytest's own output.
    """

    def boom(descriptor):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(json_config.os, "fsync", boom)


class TestFailedWriteLeavesTheOriginal:
    def test_write_failure__original_is_untouched(self, tmp_path, disk_full):
        config = tmp_path / ".claude.json"
        original = {"projects": {"/work": {"trusted": True}}, "theme": "dark"}
        config.write_text(json.dumps(original))

        with pytest.raises(OSError):
            _merge(config)

        assert json.loads(config.read_text()) == original

    def test_write_failure__no_staging_file_left_behind(self, tmp_path, disk_full):
        config = tmp_path / ".claude.json"
        config.write_text("{}")

        with pytest.raises(OSError):
            _merge(config)

        assert sorted(p.name for p in tmp_path.iterdir()) == [".claude.json"]


class TestSuccessfulWrite:
    def test_preserves_every_other_key(self, tmp_path):
        config = tmp_path / ".claude.json"
        config.write_text(json.dumps({"theme": "dark", "projects": {"/work": {}}}))

        _merge(config)

        written = json.loads(config.read_text())
        assert written["theme"] == "dark"
        assert written["projects"] == {"/work": {}}
        assert written["mcpServers"]["opik"]["command"] == "uvx"

    def test_creates_the_file_when_missing(self, tmp_path):
        config = tmp_path / "nested" / "mcp.json"

        assert _merge(config) is True
        assert json.loads(config.read_text())["mcpServers"]["opik"]

    def test_no_staging_file_survives(self, tmp_path):
        config = tmp_path / "mcp.json"

        _merge(config)

        assert [p.name for p in tmp_path.iterdir()] == ["mcp.json"]


class TestPermissions:
    """The file holds an API key, but its mode is only ours to set when we make it."""

    def test_file_we_create__is_owner_only(self, tmp_path):
        config = tmp_path / "mcp.json"

        _merge(config)

        assert stat.S_IMODE(config.stat().st_mode) == 0o600

    def test_file_that_existed__keeps_its_own_mode(self, tmp_path):
        config = tmp_path / "mcp.json"
        config.write_text("{}")
        os.chmod(config, 0o644)

        _merge(config)

        assert stat.S_IMODE(config.stat().st_mode) == 0o644


class TestSymlinkedConfig:
    """A symlinked config is followed, not replaced.

    Dotfile managers (chezmoi, stow, yadm) symlink editor configs into a tracked
    repo. `os.replace` on the link swaps it for a regular file, so the tracked
    file never receives the change and the link is gone — a regression the plain
    `write_text` this replaced did not have, because it wrote through the link.
    """

    def test_symlink_is_preserved(self, tmp_path):
        target = tmp_path / "tracked.json"
        target.write_text('{"keepMe": 1}')
        link = tmp_path / "mcp.json"
        link.symlink_to(target)

        _merge(link)

        assert link.is_symlink()

    def test_write_lands_in_the_target(self, tmp_path):
        target = tmp_path / "tracked.json"
        target.write_text('{"keepMe": 1}')
        link = tmp_path / "mcp.json"
        link.symlink_to(target)

        _merge(link)

        written = json.loads(target.read_text())
        assert written["mcpServers"]["opik"]["command"] == "uvx"
        assert written["keepMe"] == 1, "the tracked file kept its other content"

    def test_broken_symlink__creates_the_target(self, tmp_path):
        """A link into a repo that has not been checked out yet still works."""
        link = tmp_path / "mcp.json"
        link.symlink_to(tmp_path / "sub" / "target.json")

        _merge(link)

        assert link.is_symlink()
        assert json.loads((tmp_path / "sub" / "target.json").read_text())["mcpServers"]

    def test_no_staging_file_beside_the_target(self, tmp_path):
        target = tmp_path / "tracked.json"
        target.write_text("{}")
        link = tmp_path / "mcp.json"
        link.symlink_to(target)

        _merge(link)

        assert sorted(p.name for p in tmp_path.iterdir()) == [
            "mcp.json",
            "tracked.json",
        ]


class TestChmodIsPortable:
    def test_mode_is_applied_by_path_not_descriptor(self, tmp_path, monkeypatch):
        """`os.chmod` rejects a descriptor on Windows, where this also runs.

        `os.chmod in os.supports_fd` is false there, so passing the `mkstemp`
        descriptor raised instead of preserving the mode.
        """
        config = tmp_path / "mcp.json"
        config.write_text("{}")
        os.chmod(config, 0o644)
        seen = []

        real_chmod = os.chmod

        def recording_chmod(path, mode, *args, **kwargs):
            seen.append(path)
            return real_chmod(path, mode, *args, **kwargs)

        monkeypatch.setattr(json_config.os, "chmod", recording_chmod)

        _merge(config)

        assert seen, "the existing mode should have been reapplied"
        assert not any(isinstance(p, int) for p in seen), (
            f"chmod was called with a file descriptor: {seen}"
        )
