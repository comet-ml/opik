import json
import pathlib

import pytest

from opik.configurator.skills import manifest


@pytest.fixture
def fake_home(monkeypatch, tmp_path):
    monkeypatch.setattr(pathlib.Path, "home", classmethod(lambda cls: tmp_path))
    return tmp_path


def _skill_on_disk(home, name):
    skill_dir = home / ".agents" / "skills" / name
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text("body", encoding="utf-8")
    return skill_dir


class TestWriteAndRead:
    def test_write_then_read__roundtrips(self, fake_home):
        manifest.write(names=["opik", "instrument"], content_hash="abc", ref="main")

        payload = manifest.read()
        assert payload["skills"] == ["instrument", "opik"]
        assert payload["contentHash"] == "abc"
        assert payload["ref"] == "main"
        assert payload["source"] == "comet-ml/opik-skills"

    def test_write__creates_the_directory(self, fake_home):
        manifest.write(names=["opik"])
        assert manifest.manifest_path().exists()

    def test_read__missing_file__returns_none(self, fake_home):
        assert manifest.read() is None

    @pytest.mark.parametrize("payload", ["{ not json", '"bare string"', "[]"])
    def test_read__malformed__returns_none(self, fake_home, payload):
        path = manifest.manifest_path()
        path.parent.mkdir(parents=True)
        path.write_text(payload, encoding="utf-8")

        assert manifest.read() is None

    def test_write__unwritable_location__does_not_raise(self, fake_home, monkeypatch):
        """A manifest is bookkeeping; failing to write it must not fail an install."""
        monkeypatch.setattr(
            pathlib.Path,
            "write_text",
            lambda self, *a, **k: (_ for _ in ()).throw(OSError("read-only")),
        )

        manifest.write(names=["opik"])  # must not raise


class TestCollectStatus:
    def test_collect_status__recorded_and_present__is_ours(self, fake_home):
        _skill_on_disk(fake_home, "opik")
        manifest.write(names=["opik"], content_hash="deadbeef", ref="main")

        statuses = manifest.collect_status()

        assert len(statuses) == 1
        assert statuses[0].name == "opik"
        assert statuses[0].installed_by_opik is True
        assert statuses[0].content_hash == "deadbeef"

    def test_collect_status__present_but_unrecorded__is_flagged_external(
        self, fake_home
    ):
        """Somebody installed the pack by hand or with another tool; say so."""
        _skill_on_disk(fake_home, "opik")

        statuses = manifest.collect_status()

        assert [s.name for s in statuses] == ["opik"]
        assert statuses[0].installed_by_opik is False
        assert statuses[0].content_hash is None

    def test_collect_status__recorded_but_deleted__is_omitted(self, fake_home):
        manifest.write(names=["opik"], content_hash="abc")

        assert manifest.collect_status() == []

    def test_collect_status__foreign_skill_in_shared_dir__is_ignored(self, fake_home):
        """The shared directory belongs to every tool, not just Opik."""
        _skill_on_disk(fake_home, "somebody-elses-skill")

        assert manifest.collect_status() == []

    def test_collect_status__reports_claude_code_link_state(self, fake_home):
        _skill_on_disk(fake_home, "opik")
        manifest.write(names=["opik"])
        claude_skills = fake_home / ".claude" / "skills"
        claude_skills.mkdir(parents=True)
        (claude_skills / "opik").symlink_to(
            fake_home / ".agents" / "skills" / "opik", target_is_directory=True
        )

        assert manifest.collect_status()[0].linked_hosts == {"claude-code": True}

    def test_collect_status__missing_link__is_reported_false(self, fake_home):
        _skill_on_disk(fake_home, "opik")
        manifest.write(names=["opik"])

        assert manifest.collect_status()[0].linked_hosts == {"claude-code": False}

    def test_collect_status__directory_without_skill_md__is_omitted(self, fake_home):
        (fake_home / ".agents" / "skills" / "opik").mkdir(parents=True)
        manifest.write(names=["opik"])

        assert manifest.collect_status() == []

    def test_collect_status__no_shared_dir_at_all__is_empty(self, fake_home):
        assert manifest.collect_status() == []

    def test_collect_status__manifest_skills_not_a_list__is_tolerated(self, fake_home):
        _skill_on_disk(fake_home, "opik")
        path = manifest.manifest_path()
        path.write_text(json.dumps({"skills": "opik"}), encoding="utf-8")

        statuses = manifest.collect_status()

        # Falls back to on-disk detection rather than crashing.
        assert [s.name for s in statuses] == ["opik"]
        assert statuses[0].installed_by_opik is False
