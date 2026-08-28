import io
import tarfile

import pytest

from opik.configurator.skills import pack


def _archive(entries, root="comet-ml-opik-skills-abc123", extra_members=None):
    """Build a GitHub-style tarball in memory. `entries` maps path -> bytes."""
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for relative_path, content in entries.items():
            info = tarfile.TarInfo(f"{root}/{relative_path}")
            info.size = len(content)
            tar.addfile(info, io.BytesIO(content))
        for member in extra_members or []:
            tar.addfile(member)
    return buffer.getvalue()


def _pack_entries():
    return {
        "README.md": b"not a skill",
        "skills/opik/SKILL.md": b"---\nname: opik\n---\nbody",
        "skills/opik/references/tracing.md": b"tracing reference",
        "skills/instrument/SKILL.md": b"---\nname: instrument\n---\nbody",
    }


class TestReadArchive:
    def test_read_archive__collects_skills_and_ignores_other_files(self):
        result = pack._read_archive(_archive(_pack_entries()), ref="main")

        assert result.names == ["instrument", "opik"]
        assert result.skills["opik"]["SKILL.md"].startswith(b"---")
        assert "references/tracing.md" in result.skills["opik"]
        assert "README.md" not in result.skills.get("opik", {})

    def test_read_archive__records_the_ref_it_was_asked_for(self):
        assert pack._read_archive(_archive(_pack_entries()), ref="main").ref == "main"

    def test_read_archive__skill_without_a_skill_md__is_dropped(self):
        entries = {"skills/half-baked/notes.md": b"no SKILL.md here"}
        entries.update({"skills/opik/SKILL.md": b"real"})

        result = pack._read_archive(_archive(entries), ref="main")

        assert result.names == ["opik"]

    def test_read_archive__no_skills_at_all__raises_pack_error(self):
        with pytest.raises(pack.PackError, match="no skills"):
            pack._read_archive(_archive({"README.md": b"nothing"}), ref="main")

    def test_read_archive__not_a_tarball__raises_pack_error(self):
        with pytest.raises(pack.PackError, match="could not be read"):
            pack._read_archive(b"definitely not gzip", ref="main")

    def test_read_archive__oversized_member__is_skipped(self, monkeypatch):
        monkeypatch.setattr(pack, "MAX_MEMBER_BYTES", 4)
        entries = {
            "skills/opik/SKILL.md": b"tiny",
            "skills/opik/huge.md": b"x" * 100,
        }

        result = pack._read_archive(_archive(entries), ref="main")

        assert "huge.md" not in result.skills["opik"]
        assert "SKILL.md" in result.skills["opik"]

    def test_read_archive__symlink_member__is_not_extracted(self):
        """A documentation pack has no legitimate links; treat them as hostile."""
        link = tarfile.TarInfo("comet-ml-opik-skills-abc/skills/opik/evil")
        link.type = tarfile.SYMTYPE
        link.linkname = "/etc/passwd"

        result = pack._read_archive(
            _archive({"skills/opik/SKILL.md": b"real"}, extra_members=[link]),
            ref="main",
        )

        assert list(result.skills["opik"]) == ["SKILL.md"]

    @pytest.mark.parametrize(
        "traversal_path",
        [
            "skills/../../../etc/passwd/SKILL.md",
            "skills/opik/../../escape/SKILL.md",
        ],
    )
    def test_read_archive__path_traversal__is_rejected(self, traversal_path):
        entries = {"skills/opik/SKILL.md": b"real", traversal_path: b"pwned"}

        result = pack._read_archive(_archive(entries), ref="main")

        # The traversal member must be dropped entirely, and the legitimate skill
        # kept. Asserting the whole structure rather than a property of it: the
        # previous version ended in `or True`, so it passed whatever the parser
        # returned — including an accepted `../../` entry.
        assert result.skills == {"opik": {"SKILL.md": b"real"}}


class TestContentHash:
    def test_content_hash__is_stable_across_reads(self):
        archive = _archive(_pack_entries())

        first = pack._read_archive(archive, ref="main").content_hash
        second = pack._read_archive(archive, ref="main").content_hash

        assert first == second

    def test_content_hash__changes_when_content_changes(self):
        original = pack._read_archive(_archive(_pack_entries()), ref="main")
        entries = _pack_entries()
        entries["skills/opik/SKILL.md"] = b"---\nname: opik\n---\ndifferent body"
        changed = pack._read_archive(_archive(entries), ref="main")

        assert original.content_hash != changed.content_hash

    def test_content_hash__ignores_the_tarball_root_name(self):
        """The root dir is named after the ref, so it must not affect the version."""
        entries = _pack_entries()
        a = pack._read_archive(_archive(entries, root="repo-main"), ref="main")
        b = pack._read_archive(_archive(entries, root="repo-abc123"), ref="main")

        assert a.content_hash == b.content_hash


class TestIsSafeRelativePath:
    @pytest.mark.parametrize("value", ["SKILL.md", "references/a.md", "a/b/c.md"])
    def test_safe_paths(self, value):
        assert pack._is_safe_relative_path(value) is True

    @pytest.mark.parametrize(
        "value", ["", "/etc/passwd", "../escape", "a/../../b", "a\\b", "."]
    )
    def test_unsafe_paths(self, value):
        assert pack._is_safe_relative_path(value) is False


class TestWriteSkill:
    def test_write_skill__creates_nested_files(self, tmp_path):
        pack.write_skill(
            tmp_path, "opik", {"SKILL.md": b"body", "references/a.md": b"ref"}
        )

        assert (tmp_path / "opik" / "SKILL.md").read_bytes() == b"body"
        assert (tmp_path / "opik" / "references" / "a.md").read_bytes() == b"ref"

    def test_write_skill__replaces_an_existing_skill_entirely(self, tmp_path):
        """A file dropped from the pack must not survive an update."""
        stale = tmp_path / "opik"
        stale.mkdir()
        (stale / "SKILL.md").write_bytes(b"old")
        (stale / "gone.md").write_bytes(b"removed upstream")

        pack.write_skill(tmp_path, "opik", {"SKILL.md": b"new"})

        assert (tmp_path / "opik" / "SKILL.md").read_bytes() == b"new"
        assert not (tmp_path / "opik" / "gone.md").exists()

    def test_write_skill__leaves_no_staging_directory_behind(self, tmp_path):
        pack.write_skill(tmp_path, "opik", {"SKILL.md": b"body"})

        assert [p.name for p in tmp_path.iterdir()] == ["opik"]

    def test_write_skill__replaces_a_symlink_without_following_it(self, tmp_path):
        elsewhere = tmp_path / "elsewhere"
        elsewhere.mkdir()
        (elsewhere / "sentinel").write_bytes(b"must survive")
        destination = tmp_path / "dest"
        destination.mkdir()
        (destination / "opik").symlink_to(elsewhere, target_is_directory=True)

        pack.write_skill(destination, "opik", {"SKILL.md": b"body"})

        assert (destination / "opik").is_dir()
        assert not (destination / "opik").is_symlink()
        assert (elsewhere / "sentinel").exists()


class TestDownload:
    """The pack is fetched with a plain client, streamed, and size-capped.

    `httpx.MockTransport` rather than a hand-rolled fake client: `download` now
    streams the response, so a stub with a `get` method no longer stands in for
    the real thing — and the transport exercises the streaming path itself.
    """

    @staticmethod
    def _transport(monkeypatch, handler):
        import httpx

        real_client = httpx.Client

        def client(**kwargs):
            kwargs.pop("transport", None)
            return real_client(transport=httpx.MockTransport(handler), **kwargs)

        monkeypatch.setattr(pack.httpx, "Client", client)

    def test_download__non_200__raises_pack_error(self, monkeypatch):
        import httpx

        self._transport(monkeypatch, lambda request: httpx.Response(404))

        with pytest.raises(pack.PackError, match="HTTP 404"):
            pack.download()

    def test_download__network_error__raises_pack_error(self, monkeypatch):
        import httpx

        def boom(request):
            raise httpx.ConnectError("no route to host")

        self._transport(monkeypatch, boom)

        with pytest.raises(pack.PackError, match="could not download"):
            pack.download()

    def test_download__oversized_archive__is_refused(self, monkeypatch):
        import httpx

        monkeypatch.setattr(pack, "MAX_ARCHIVE_BYTES", 8)
        self._transport(
            monkeypatch, lambda request: httpx.Response(200, content=b"x" * 100)
        )

        with pytest.raises(pack.PackError, match="larger than"):
            pack.download()

    def test_download__does_not_use_opiks_own_http_factory(self):
        """Opik's factory applies hooks meant for calls to the Opik API.

        Nothing leaks today, but a build registering a header-injecting hook would
        start sending Opik headers to codeload.github.com. The module not importing
        the factory at all is what keeps that door shut.
        """
        assert not hasattr(pack, "httpx_client")


class TestWriteSkillPathGuard:
    """`write_skill` deletes what it resolves, and the name comes from the archive.

    An empty name resolves to the destination root itself, and `..` or an absolute
    name escapes it — so a bad or hostile archive could aim the `rmtree` somewhere
    it was never meant to reach.
    """

    @pytest.mark.parametrize("name", ["", ".", "..", "../escape", "/etc", "a/b"])
    def test_names_that_are_not_a_direct_child__are_refused(self, tmp_path, name):
        with pytest.raises(pack.PackError):
            pack.write_skill(tmp_path, name, {"SKILL.md": b"x"})

    def test_refusing__does_not_touch_the_destination(self, tmp_path):
        sentinel = tmp_path / "keep-me"
        sentinel.write_text("important")

        with pytest.raises(pack.PackError):
            pack.write_skill(tmp_path, "..", {"SKILL.md": b"x"})

        assert sentinel.read_text() == "important"

    def test_an_ordinary_name__still_works(self, tmp_path):
        pack.write_skill(tmp_path, "opik", {"SKILL.md": b"body"})

        assert (tmp_path / "opik" / "SKILL.md").read_bytes() == b"body"
