import json
import os
import pathlib
import stat
import tempfile
from typing import Any, Dict


def merge_server_into_json_file(
    config_path: pathlib.Path,
    top_level_key: str,
    server_name: str,
    server_block: Dict[str, Any],
) -> bool:
    """Idempotently register ``server_name`` under ``top_level_key`` in a host's
    JSON config file, preserving every other key already present.

    The file is created (with parents) if it does not exist. Re-running updates
    the existing ``server_name`` entry in place rather than duplicating it.

    Returns ``True`` when the entry was newly added, ``False`` when an existing
    entry was overwritten.

    Raises ``ValueError`` if the file exists but is not valid JSON (for example a
    JSONC file with comments — a ``json.JSONDecodeError``) or parses to something
    other than a JSON object; the caller is expected to fall back to printing
    manual instructions in that case.
    """
    if config_path.exists() and config_path.stat().st_size > 0:
        existing_config = json.loads(config_path.read_text(encoding="utf-8"))
        if not isinstance(existing_config, dict):
            raise ValueError(
                f"expected a JSON object at the root, got "
                f"{type(existing_config).__name__}"
            )
    else:
        existing_config = {}

    servers = existing_config.setdefault(top_level_key, {})
    was_new = server_name not in servers
    servers[server_name] = server_block

    _write_atomically(config_path, json.dumps(existing_config, indent=2) + "\n")

    return was_new


def _write_atomically(config_path: pathlib.Path, contents: str) -> None:
    """Replace ``config_path`` in one step, or leave it exactly as it was.

    These are files Opik does not own, and they hold far more than an MCP block:
    ``~/.claude.json`` is the whole of Claude Code's user state. A plain
    ``write_text`` truncates before it writes, so a full disk, a signal or a
    killed process leaves the user's editor config unparseable — and the caller
    catches ``OSError`` and prints a tidy message, by which point the damage is
    done. Writing a sibling temp file and renaming it means the worst case is a
    stray temp file next to an untouched config.

    The temp file goes in the same directory because ``os.replace`` is only
    atomic within a filesystem, and ``mkstemp`` creates it 0600: what we are
    about to write includes an API key, so it should not exist even briefly at
    whatever the umask allows. A file we are *creating* keeps that 0600 — but one
    that already existed keeps the mode its owner chose, since tightening
    permissions on another tool's config is not ours to decide.

    A symlinked config is followed and its *target* replaced. Dotfile managers
    (chezmoi, stow, yadm) routinely symlink editor configs into a tracked repo,
    and ``os.replace`` on the link itself would swap it for a regular file: the
    tracked file would never receive the change, and the link would be gone. A
    plain ``write_text`` wrote through the link, so following it keeps that
    behaviour rather than quietly breaking someone's dotfiles.
    """
    destination = (
        pathlib.Path(os.path.realpath(config_path))
        if config_path.is_symlink()
        else config_path
    )
    destination.parent.mkdir(parents=True, exist_ok=True)

    previous_mode = (
        stat.S_IMODE(destination.stat().st_mode) if destination.exists() else None
    )

    descriptor, staging_name = tempfile.mkstemp(
        dir=str(destination.parent), prefix=f".{destination.name}.", suffix=".tmp"
    )
    staging = pathlib.Path(staging_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(contents)
            handle.flush()
            # Without this the rename can land before the bytes do, which turns a
            # crash into a valid-looking but empty config.
            os.fsync(handle.fileno())
        if previous_mode is not None:
            # By path rather than the descriptor: `os.chmod` only accepts a
            # descriptor where `os.chmod in os.supports_fd`, which is false on
            # Windows — there it raises, and this runs on Windows too. Applied
            # after the write so the key is never in a file wider than 0600 for
            # longer than the final mode allows anyway.
            os.chmod(staging, previous_mode)
        os.replace(staging, destination)
    except BaseException:
        staging.unlink(missing_ok=True)
        raise
