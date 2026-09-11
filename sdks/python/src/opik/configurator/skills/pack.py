"""Fetch and unpack the Opik skill pack from its GitHub repository.

The pack is a handful of Markdown files, so this is a tarball download and a
filtered extract — no Node, no third-party installer CLI, nothing new in the
dependency list beyond ``httpx``, which the SDK already ships.

Extraction is deliberately hand-rolled rather than ``TarFile.extractall``. The
``filter="data"`` argument that makes ``extractall`` safe only exists on Python
3.12+, and the SDK supports 3.10, so every member is validated here instead:
regular files only, no absolute paths, no ``..`` traversal, nothing outside the
skill directory we asked for.
"""

import dataclasses
import hashlib
import io
import logging
import pathlib
import shutil
import tarfile
from typing import Dict, Final, List

import httpx


LOGGER = logging.getLogger(__name__)

REPOSITORY: Final[str] = "comet-ml/opik-skills"
DEFAULT_REF: Final[str] = "main"

# The skills the pack ships. Used to recognise an install we did not record —
# never to decide what to write, which always comes from the archive itself.
KNOWN_SKILL_NAMES: Final[tuple] = ("opik", "instrument")
TARBALL_URL_TEMPLATE: Final[str] = (
    "https://codeload.github.com/{repository}/tar.gz/refs/heads/{ref}"
)
DOWNLOAD_TIMEOUT_SECONDS: Final[float] = 60.0

# Where skills live inside the repository.
_SKILLS_PREFIX: Final[str] = "skills/"

# A skill pack is documentation; anything of this size is not one.
MAX_ARCHIVE_BYTES: Final[int] = 25 * 1024 * 1024
MAX_MEMBER_BYTES: Final[int] = 2 * 1024 * 1024


@dataclasses.dataclass
class SkillPack:
    """Skill name -> {relative file path: bytes}, plus what version it is."""

    ref: str
    skills: Dict[str, Dict[str, bytes]]

    @property
    def names(self) -> List[str]:
        return sorted(self.skills)

    @property
    def content_hash(self) -> str:
        """A stable digest of the pack's contents.

        Used instead of a commit sha because the codeload tarball names its root
        directory after the *ref* we asked for, not the commit — so a sha is not
        actually available without a second request. Hashing what we downloaded
        answers the only question the manifest needs ("has this changed?") without
        one, and does not depend on GitHub's naming staying put.
        """
        digest = hashlib.sha256()
        for skill_name in sorted(self.skills):
            for file_path in sorted(self.skills[skill_name]):
                digest.update(skill_name.encode("utf-8"))
                digest.update(file_path.encode("utf-8"))
                digest.update(self.skills[skill_name][file_path])
        return digest.hexdigest()


class PackError(Exception):
    """The pack could not be fetched or read. Carries a user-facing message."""


def download(ref: str = DEFAULT_REF) -> SkillPack:
    """Fetch the pack at ``ref``. Raises :class:`PackError` with a usable message."""
    url = TARBALL_URL_TEMPLATE.format(repository=REPOSITORY, ref=ref)
    try:
        # A plain client, deliberately not Opik's `httpx_client` factory: that
        # applies `hooks.httpx_client_hook` and honours `_OPIK_HTTP_PROXY`, which
        # exist to decorate calls to the Opik API. Nothing leaks today — passing
        # no api_key/workspace keeps the auth headers off — but a build that
        # registers a header-injecting hook would start sending them to GitHub.
        with httpx.Client(follow_redirects=True) as client:
            with client.stream(
                "GET", url, timeout=DOWNLOAD_TIMEOUT_SECONDS
            ) as response:
                if response.status_code != 200:
                    raise PackError(
                        f"could not download the Opik skill pack from {url} "
                        f"(HTTP {response.status_code})"
                    )
                archive = _read_capped(response, url)
    except (httpx.HTTPError, OSError) as error:
        raise PackError(f"could not download the Opik skill pack from {url}: {error}")

    return _read_archive(archive, ref=ref)


def _read_capped(response: httpx.Response, url: str) -> bytes:
    """Read the body, stopping as soon as it exceeds :data:`MAX_ARCHIVE_BYTES`.

    Streamed with a running total rather than checking ``len(response.content)``:
    touching ``.content`` materialises the whole body first, so the limit could
    only ever report the memory it was supposed to prevent us from using.
    """
    chunks = []
    total = 0
    for chunk in response.iter_bytes():
        total += len(chunk)
        if total > MAX_ARCHIVE_BYTES:
            raise PackError(
                f"the Opik skill pack archive at {url} is larger than "
                f"{MAX_ARCHIVE_BYTES} bytes; refusing to download it"
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _read_archive(archive: bytes, ref: str) -> SkillPack:
    try:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
            return _collect_skills(tar, ref=ref)
    except tarfile.TarError as error:
        raise PackError(f"the Opik skill pack archive could not be read: {error}")


def _collect_skills(tar: tarfile.TarFile, ref: str) -> SkillPack:
    skills: Dict[str, Dict[str, bytes]] = {}

    for member in tar.getmembers():
        # GitHub tarballs nest everything under a single top-level directory;
        # strip it so paths are relative to the repository root.
        parts = pathlib.PurePosixPath(member.name).parts
        if len(parts) < 2:
            continue

        relative = "/".join(parts[1:])
        if not relative.startswith(_SKILLS_PREFIX):
            continue
        if not member.isfile():
            # Directories are implied by the files we keep; links and devices are
            # never legitimate in a documentation pack.
            continue
        if member.size > MAX_MEMBER_BYTES:
            LOGGER.debug("Skipping oversized skill file %s", relative)
            continue

        skill_relative = relative[len(_SKILLS_PREFIX) :]
        skill_parts = pathlib.PurePosixPath(skill_relative).parts
        if len(skill_parts) < 2:
            continue

        skill_name = skill_parts[0]
        inner_path = "/".join(skill_parts[1:])
        if not _is_safe_relative_path(skill_name) or not _is_safe_relative_path(
            inner_path
        ):
            LOGGER.debug("Skipping unsafe skill path %s", relative)
            continue

        extracted = tar.extractfile(member)
        if extracted is None:
            continue
        skills.setdefault(skill_name, {})[inner_path] = extracted.read()

    skills = {name: files for name, files in skills.items() if "SKILL.md" in files}
    if not skills:
        raise PackError(
            "the Opik skill pack archive contained no skills; the repository "
            "layout may have changed"
        )
    return SkillPack(ref=ref, skills=skills)


def _is_safe_relative_path(value: str) -> bool:
    """Reject anything that could escape the destination directory."""
    if not value or value.startswith("/") or "\\" in value:
        return False
    parts = pathlib.PurePosixPath(value).parts
    # `PurePosixPath(".").parts` is empty, which would make the `all()` below
    # vacuously true and let "." through as a skill name.
    if not parts:
        return False
    return all(part not in ("", ".", "..") for part in parts)


def _child_of(root: pathlib.Path, name: str) -> pathlib.Path:
    """Resolve ``root/name``, refusing anything that is not directly inside root.

    This function's callers go on to `rmtree` what it returns, and ``name`` comes
    from the downloaded archive's directory entries. An empty name resolves to
    `root` itself, and `..` or an absolute name escapes it — so a bad or hostile
    archive could aim the delete somewhere it was never meant to reach. Checked
    here rather than at the delete, so there is one place to be sure of.
    """
    if not name or name in (".", "..") or name != pathlib.Path(name).name:
        raise PackError(f"refusing to use {name!r} as a skill directory name")

    child = root / name
    if child.parent.resolve() != root.resolve():
        raise PackError(f"refusing to write a skill outside {root}")

    return child


def write_skill(
    destination_root: pathlib.Path, name: str, files: Dict[str, bytes]
) -> None:
    """Replace ``destination_root/name`` with ``files``.

    Written to a sibling staging directory and swapped in, so an interrupted
    download cannot leave a half-written skill that an assistant would then read.
    """
    target = _child_of(destination_root, name)
    staging = _child_of(destination_root, f".{name}.opik-staging")

    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    for relative_path, content in files.items():
        file_path = staging / relative_path
        file_path.parent.mkdir(parents=True, exist_ok=True)
        file_path.write_bytes(content)

    if target.exists() or target.is_symlink():
        if target.is_dir() and not target.is_symlink():
            shutil.rmtree(target)
        else:
            target.unlink()
    staging.replace(target)
