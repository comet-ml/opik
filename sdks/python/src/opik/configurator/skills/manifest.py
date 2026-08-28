"""Record of what Opik installed, so a re-run can tell a change from a no-op.

Kept in ``~/.agents/skills/.opik-skills.json``, next to the skills themselves and
separate from any other tool's bookkeeping. It records a digest of the pack
contents, which is how "is this current?" gets answered for what is otherwise
just a directory of Markdown files.

A skill directory that exists without a manifest entry is reported as installed
but of unknown origin rather than ignored — a user may well have installed the
pack by hand or with another tool, and status should not pretend otherwise.
"""

import datetime
import json
import logging
import pathlib
from typing import Dict, List, Optional

from opik.configurator.skills import roots as skills_roots

LOGGER = logging.getLogger(__name__)

MANIFEST_FILENAME = ".opik-skills.json"
MANIFEST_VERSION = 1


def manifest_path() -> pathlib.Path:
    return skills_roots.shared_skills_dir() / MANIFEST_FILENAME


def write(
    names: List[str],
    content_hash: Optional[str] = None,
    ref: Optional[str] = None,
    hosts: Optional[List[str]] = None,
) -> None:
    """Record an install. Best-effort: a manifest we cannot write is not fatal."""
    payload = {
        "version": MANIFEST_VERSION,
        "source": skills_roots_source(),
        "ref": ref,
        "contentHash": content_hash,
        "installedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "skills": sorted(names),
        # Which assistants were chosen, so `update` re-links the same set instead
        # of guessing. Absent in manifests written before this field existed.
        "hosts": sorted(hosts) if hosts is not None else None,
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
