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
