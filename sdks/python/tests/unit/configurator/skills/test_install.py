import pathlib
from unittest import mock

import pytest

from opik.configurator.skills import install, manifest, pack


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
        assert install.setup_skills(["codex"]) is True

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
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        assert install.setup_skills(["codex"]) is False
        assert not (fake_home / ".agents").exists()
        warned = " ".join(str(c) for c in logger_spy.warning.call_args_list)
        assert "network is down" in warned

    def test_setup_skills__write_failure__returns_false(
        self, fake_home, fake_pack, monkeypatch
    ):
        monkeypatch.setattr(
            pack,
            "write_skill",
            mock.Mock(side_effect=OSError("read-only file system")),
        )

        assert install.setup_skills(["codex"]) is False

    def test_setup_skills__no_supported_host__returns_false(
        self, fake_home, monkeypatch
    ):
        download_spy = mock.Mock()
        monkeypatch.setattr(pack, "download", download_spy)

        assert install.setup_skills(["emacs"]) is False
        download_spy.assert_not_called()

    def test_setup_skills__empty_host_list__returns_false(self, fake_home, monkeypatch):
        monkeypatch.setattr(pack, "download", mock.Mock())
        assert install.setup_skills([]) is False

    def test_setup_skills__rerun__replaces_and_stays_idempotent(
        self, fake_home, fake_pack
    ):
        install.setup_skills(["claude-code"])
        assert install.setup_skills(["claude-code"]) is True

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

    def test_link_for_host__failure_is_reported_not_raised(
        self, fake_home, monkeypatch
    ):
        monkeypatch.setattr(
            install,
            "_replace_with_link",
            mock.Mock(side_effect=OSError("permission denied")),
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install._link_for_host(
            "claude-code", ["opik"], fake_home / ".agents" / "skills"
        )

        warned = " ".join(str(c) for c in logger_spy.warning.call_args_list)
        assert "Could not link" in warned


class TestUninstall:
    def test_uninstall__removes_shared_files_and_links(self, fake_home, fake_pack):
        install.setup_skills(["claude-code"])

        removed = install.uninstall_skills()

        assert sorted(removed) == ["instrument", "opik"]
        assert not (fake_home / ".agents" / "skills" / "opik").exists()
        assert not (fake_home / ".claude" / "skills" / "opik").exists()

    def test_uninstall__leaves_skills_we_did_not_install(self, fake_home):
        """A hand-made skill sharing our name must survive."""
        skill_dir = fake_home / ".agents" / "skills" / "opik"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_bytes(b"hand written")

        assert install.uninstall_skills() == []
        assert (skill_dir / "SKILL.md").exists()

    def test_uninstall__nothing_installed__is_a_noop(self, fake_home):
        assert install.uninstall_skills() == []


class TestPluginOverlapWarning:
    def test_warn__claude_code_with_plugin_skill__notes_the_overlap(
        self, fake_home, monkeypatch
    ):
        (fake_home / ".claude/plugins/marketplaces/opik/skills/opik").mkdir(
            parents=True
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install._warn_on_claude_code_plugin_overlap(["claude-code"])

        logged = " ".join(str(c) for c in logger_spy.info.call_args_list)
        assert "also ships an `opik` skill" in logged

    def test_warn__no_plugin__stays_quiet(self, fake_home, monkeypatch):
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install._warn_on_claude_code_plugin_overlap(["claude-code"])

        logger_spy.info.assert_not_called()

    def test_warn__claude_code_not_targeted__stays_quiet(self, fake_home, monkeypatch):
        (fake_home / ".claude/plugins/marketplaces/opik/skills/opik").mkdir(
            parents=True
        )
        logger_spy = mock.Mock()
        monkeypatch.setattr(install, "LOGGER", logger_spy)

        install._warn_on_claude_code_plugin_overlap(["codex"])

        logger_spy.info.assert_not_called()


class TestUpdateSkills:
    @pytest.fixture
    def installed(self, fake_home, fake_pack):
        install.setup_skills(["claude-code"])
        return fake_pack

    def test_update__nothing_installed__says_so_without_downloading(
        self, fake_home, monkeypatch
    ):
        download_spy = mock.Mock()
        monkeypatch.setattr(pack, "download", download_spy)

        result = install.update_skills()

        assert result.changed is False
        assert "opik skills configure" in result.detail
        download_spy.assert_not_called()

    def test_update__unchanged_pack__rewrites_nothing(self, installed, monkeypatch):
        """Running this on a schedule must be cheap when nothing moved."""
        write_spy = mock.Mock()
        monkeypatch.setattr(pack, "write_skill", write_spy)

        result = install.update_skills()

        assert result.changed is False
        assert "already up to date" in result.detail
        write_spy.assert_not_called()

    def test_update__changed_pack__rewrites_and_reports(
        self, installed, fake_home, monkeypatch
    ):
        newer = pack.SkillPack(
            ref="main",
            skills={
                "opik": {"SKILL.md": b"opik body v2"},
                "instrument": {"SKILL.md": b"instrument body"},
            },
        )
        monkeypatch.setattr(pack, "download", mock.Mock(return_value=newer))

        result = install.update_skills()

        assert result.changed is True
        assert (
            fake_home / ".agents/skills/opik/SKILL.md"
        ).read_bytes() == b"opik body v2"
        assert install_manifest_hash(fake_home) == newer.content_hash

    def test_update__skill_dropped_upstream__is_removed_everywhere(
        self, installed, fake_home, monkeypatch
    ):
        """A rename upstream must not leave the old name for the agent to read."""
        renamed = pack.SkillPack(
            ref="main",
            skills={
                "opik": {"SKILL.md": b"opik body"},
                "evaluate": {"SKILL.md": b"evaluate body"},
            },
        )
        monkeypatch.setattr(pack, "download", mock.Mock(return_value=renamed))

        result = install.update_skills()

        assert result.removed == ["instrument"]
        assert result.added == ["evaluate"]
        assert not (fake_home / ".agents/skills/instrument").exists()
        assert not (fake_home / ".claude/skills/instrument").exists()
        assert (fake_home / ".claude/skills/evaluate").is_symlink()

    def test_update__relinks_the_recorded_hosts_only(self, installed, fake_home):
        """Cursor reads the shared dir, so it must not gain a pointless link."""
        newer = pack.SkillPack(ref="main", skills={"opik": {"SKILL.md": b"v2"}})
        with mock.patch.object(pack, "download", return_value=newer):
            install.update_skills()

        assert (fake_home / ".claude/skills/opik").is_symlink()
        assert not (fake_home / ".cursor").exists()

    def test_update__download_failure__changes_nothing(self, installed, monkeypatch):
        monkeypatch.setattr(
            pack, "download", mock.Mock(side_effect=pack.PackError("offline"))
        )

        result = install.update_skills()

        assert result.changed is False
        assert "offline" in result.detail

    def test_update__manifest_without_hosts__infers_from_existing_links(
        self, installed, fake_home, monkeypatch
    ):
        """Manifests written before the `hosts` field must still update."""
        import json

        path = manifest.manifest_path()
        payload = json.loads(path.read_text())
        del payload["hosts"]
        payload["contentHash"] = "stale"
        path.write_text(json.dumps(payload))

        newer = pack.SkillPack(ref="main", skills={"opik": {"SKILL.md": b"v2"}})
        monkeypatch.setattr(pack, "download", mock.Mock(return_value=newer))

        result = install.update_skills()

        assert result.changed is True
        assert (fake_home / ".claude/skills/opik").is_symlink()


def install_manifest_hash(home):
    import json

    return json.loads((home / ".agents/skills/.opik-skills.json").read_text())[
        "contentHash"
    ]
