"""Record of what Opik installed, so ``opik skills status`` can report it.

Kept in ``~/.agents/skills/.opik-skills.json``, next to the skills themselves and
separate from any other tool's bookkeeping. It records a digest of the pack
contents, which is how "is this current?" gets answered for what is otherwise
just a directory of Markdown files.

A skill directory that exists without a manifest entry is reported as installed
but of unknown origin rather than ignored — a user may well have installed the
pack by hand or with another tool, and status should not pretend otherwise.
"""

import dataclasses
import datetime
import json
import logging
import pathlib
from typing import Dict, List, Optional

from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)

MANIFEST_FILENAME = ".opik-skills.json"
MANIFEST_VERSION = 1


@dataclasses.dataclass
class SkillStatus:
    name: str
    path: pathlib.Path
    content_hash: Optional[str]
    installed_at: Optional[str]
    installed_by_opik: bool
    linked_hosts: Dict[str, bool] = dataclasses.field(default_factory=dict)


def manifest_path() -> pathlib.Path:
    return skills_roots.shared_skills_dir() / MANIFEST_FILENAME


def write(
    names: List[str], content_hash: Optional[str] = None, ref: Optional[str] = None
) -> None:
    """Record an install. Best-effort: a manifest we cannot write is not fatal."""
    payload = {
        "version": MANIFEST_VERSION,
        "source": skills_roots_source(),
        "ref": ref,
        "contentHash": content_hash,
        "installedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "skills": sorted(names),
    }
    path = manifest_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    except OSError:
        LOGGER.debug("Could not write the skills manifest at %s", path, exc_info=True)


def skills_roots_source() -> str:
    from opik.configurator.skills import pack as skills_pack

    return skills_pack.REPOSITORY


def read() -> Optional[Dict[str, object]]:
    path = manifest_path()
    try:
        if not path.exists() or path.stat().st_size == 0:
            return None
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return payload if isinstance(payload, dict) else None


def collect_status() -> List[SkillStatus]:
    """Every Opik skill present on disk, with where it came from and its links."""
    manifest = read() or {}
    recorded = manifest.get("skills")
    recorded_names = set(recorded) if isinstance(recorded, list) else set()
    content_hash = manifest.get("contentHash")
    installed_at = manifest.get("installedAt")

    shared_dir = skills_roots.shared_skills_dir()
    statuses: List[SkillStatus] = []

    for name in sorted(
        recorded_names | _skill_dirs_on_disk(shared_dir, recorded_names)
    ):
        skill_dir = shared_dir / name
        if not (skill_dir / "SKILL.md").exists():
            continue
        installed_by_opik = name in recorded_names
        statuses.append(
            SkillStatus(
                name=name,
                path=skill_dir,
                content_hash=str(content_hash)
                if installed_by_opik and content_hash
                else None,
                installed_at=str(installed_at)
                if installed_by_opik and installed_at
                else None,
                installed_by_opik=installed_by_opik,
                linked_hosts=_link_state(name),
            )
        )
    return statuses


def _skill_dirs_on_disk(shared_dir: pathlib.Path, recorded_names: set) -> set:
    """Opik-looking skills present on disk that the manifest does not claim.

    Restricted to names we ship, so somebody else's skills in the shared
    directory are never reported as Opik's.
    """
    from opik.configurator.skills import pack as skills_pack

    known = set(skills_pack.KNOWN_SKILL_NAMES)
    try:
        present = {entry.name for entry in shared_dir.iterdir() if entry.is_dir()}
    except OSError:
        return set()
    return (present & known) - recorded_names


def _link_state(name: str) -> Dict[str, bool]:
    state: Dict[str, bool] = {}
    for host_key in skills_roots.LINKED_HOST_KEYS:
        directory = skills_roots.link_dir(host_key)
        if directory is None:
            continue
        state[host_key] = (directory / name).exists()
    return state
