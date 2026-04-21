"""Shared path-sanitisation helper for attachment export and import."""

from pathlib import Path
from typing import Optional


def safe_attachment_path(
    base_dir: Path,
    entity_type: str,
    entity_id: str,
    file_name: str,
) -> Optional[Path]:
    """Return the safe local path for an attachment file, or None if invalid.

    Sanitises *file_name* to its bare basename so that path separators embedded
    by a malicious server cannot escape *base_dir*.  The resolved destination is
    also checked to stay within *base_dir*; None is returned (and the caller
    should skip the attachment) when either check fails.

    The canonical layout is::

        <base_dir>/attachments/<entity_type>/<entity_id>/<safe_file_name>
    """
    safe_file_name = Path(file_name).name
    if not safe_file_name or safe_file_name in (".", ".."):
        return None

    dest = base_dir / "attachments" / entity_type / entity_id / safe_file_name
    if not dest.resolve().is_relative_to(base_dir.resolve()):
        return None

    return dest
