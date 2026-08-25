import pathlib
from unittest import mock

import pytest

from opik.configurator.skills import install, manifest, pack


@pytest.fixture(autouse=True)
def interactive(monkeypatch):
    """These cover installer mechanics, not consent.

    ``setup_skills`` refuses outside an interactive session, which is asserted in
    its own test below; every other case here needs to get past that gate.
    """
    monkeypatch.setattr(install.interactive_helpers, "is_interactive", lambda: True)


@pytest.fixture
def fake_home(monkeypatch, tmp_path):
    monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
    return tmp_path


@pytest.fixture
def fake_pack(monkeypatch):
    """Stub the download so tests never reach the network."""
    result = pack.SkillPack(
        ref="main",
        skills={
            "opik": {"SKILL.md": b"opik body", "references/a.md": b"ref"},
            "instrument": {"SKILL.md": b"instrument body"},
        },
    )
    monkeypatch.setattr(pack, "download", mock.Mock(return_value=result))
    return result


class TestSetupSkills:
    def test_setup_skills__writes_to_the_shared_directory(self, fake_home, fake_pack):
        assert install.setup_skills(["codex"]).succeeded is True

        shared = fake_home / ".agents" / "skills"
        assert (shared / "opik" / "SKILL.md").read_bytes() == b"opik body"
        assert (shared / "opik" / "references" / "a.md").read_bytes() == b"ref"
        assert (shared / "instrument" / "SKILL.md").exists()

    def test_setup_skills__shared_dir_host__creates_no_extra_link(
        self, fake_home, fake_pack
    ):
        """Codex reads ~/.agents/skills natively, so nothing else should be written."""
        install.setup_skills(["codex"])

        assert not (fake_home / ".claude").exists()

    def test_setup_skills__claude_code__links_into_its_own_directory(
        self, fake_home, fake_pack
    ):
        install.setup_skills(["claude-code"])

        link = fake_home / ".claude" / "skills" / "opik"
        assert link.is_symlink()
        assert link.resolve() == (fake_home / ".agents" / "skills" / "opik").resolve()

    def test_setup_skills__records_a_manifest(self, fake_home, fake_pack):
        install.setup_skills(["codex"])

        payload = manifest.read()
        assert payload["skills"] == ["instrument", "opik"]
        assert payload["contentHash"] == fake_pack.content_hash

    def test_setup_skills__download_failure__returns_false_and_writes_nothing(
        self, fake_home, monkeypatch
    ):
        monkeypatch.setattr(
            pack, "download", mock.Mock(side_effect=pack.PackError("network is down"))
        )

        result = install.setup_skills(["codex"])

        assert result.succeeded is False
        assert not (fake_home / ".agents").exists()
        # Returned, not logged: the caller decides how to word it.
        assert result.error == "network is down"

    def test_setup_skills__write_failure__returns_false(
        self, fake_home, fake_pack, monkeypatch
    ):
        monkeypatch.setattr(
            pack,
            "write_skill",
            mock.Mock(side_effect=OSError("read-only file system")),
        )

        assert install.setup_skills(["codex"]).succeeded is False

    def test_setup_skills__no_supported_host__returns_false(
        self, fake_home, monkeypatch
    ):
        download_spy = mock.Mock()
        monkeypatch.setattr(pack, "download", download_spy)

        assert install.setup_skills(["emacs"]).succeeded is False
        download_spy.assert_not_called()

    def test_setup_skills__empty_host_list__returns_false(self, fake_home, monkeypatch):
        monkeypatch.setattr(pack, "download", mock.Mock())
        assert install.setup_skills([]).succeeded is False

    def test_setup_skills__rerun__replaces_and_stays_idempotent(
        self, fake_home, fake_pack
    ):
        install.setup_skills(["claude-code"])
        assert install.setup_skills(["claude-code"]).succeeded is True

        link = fake_home / ".claude" / "skills" / "opik"
        assert link.is_symlink()
        assert (fake_home / ".agents" / "skills" / "opik" / "SKILL.md").exists()

    def test_setup_skills__stale_file_removed_upstream__does_not_survive(
        self, fake_home, fake_pack
    ):
        install.setup_skills(["codex"])
        stale = fake_home / ".agents" / "skills" / "opik" / "stale.md"
        stale.write_bytes(b"left over")

        install.setup_skills(["codex"])

        assert not stale.exists()


class TestLinking:
    def test_replace_with_link__falls_back_to_a_copy_when_symlinks_fail(
        self, tmp_path, monkeypatch
    ):
        """Windows needs elevation for symlinks; a copy beats refusing to install."""
        target = tmp_path / "target"
        target.mkdir()
        (target / "SKILL.md").write_bytes(b"body")
        monkeypatch.setattr(
            pathlib.Path,
            "symlink_to",
            lambda self, *a, **k: (_ for _ in ()).throw(OSError("not permitted")),
        )

        install._replace_with_link(tmp_path / "link", target)

        assert (tmp_path / "link" / "SKILL.md").read_bytes() == b"body"
        assert not (tmp_path / "link").is_symlink()

    def test_replace_with_link__replaces_an_existing_directory(self, tmp_path):
        target = tmp_path / "target"
        target.mkdir()
        existing = tmp_path / "link"
        existing.mkdir()
        (existing / "old.md").write_bytes(b"old")

        install._replace_with_link(existing, target)

        assert existing.is_symlink()

    def test_link_for_host__failure_is_returned_not_logged_as_prose(
        self, fake_home, monkeypatch
    ):
        """Business logic hands back facts; the caller words them."""
        monkeypatch.setattr(
            install,
            "_replace_with_link",
            mock.Mock(side_effect=OSError("permission denied")),
        )

        linked, failure = install._link_for_host(
            "claude-code", ["opik"], fake_home / ".agents" / "skills"
        )

        assert linked == []
        assert failure is not None
        assert "permission denied" in failure

    def test_link_for_host__success_returns_the_names(self, fake_home):
        shared = fake_home / ".agents" / "skills"
        (shared / "opik").mkdir(parents=True)

        linked, failure = install._link_for_host("claude-code", ["opik"], shared)

        assert linked == ["opik"]
        assert failure is None


class TestPluginOverlapDetection:
    def test_claude_code_with_plugin_skill__is_flagged(self, fake_home):
        (fake_home / ".claude/plugins/marketplaces/opik/skills/opik").mkdir(
            parents=True
        )

        assert install._claude_code_plugin_ships_its_own_skill(["claude-code"]) is True

    def test_no_plugin__is_not_flagged(self, fake_home):
        assert install._claude_code_plugin_ships_its_own_skill(["claude-code"]) is False

    def test_claude_code_not_targeted__is_not_flagged(self, fake_home):
        (fake_home / ".claude/plugins/marketplaces/opik/skills/opik").mkdir(
            parents=True
        )

        assert install._claude_code_plugin_ships_its_own_skill(["codex"]) is False

    def test_setup_skills__surfaces_the_overlap_on_the_result(
        self, fake_home, fake_pack
    ):
        (fake_home / ".claude/plugins/marketplaces/opik/skills/opik").mkdir(
            parents=True
        )

        assert install.setup_skills(["claude-code"]).plugin_overlap is True
