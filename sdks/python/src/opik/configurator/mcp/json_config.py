import json
import pathlib
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

    Raises ``json.JSONDecodeError`` if the file exists but is not valid JSON (for
    example a JSONC file with comments); the caller is expected to fall back to
    printing manual instructions in that case.
    """
    if config_path.exists() and config_path.stat().st_size > 0:
        existing_config = json.loads(config_path.read_text(encoding="utf-8"))
    else:
        existing_config = {}

    servers = existing_config.setdefault(top_level_key, {})
    was_new = server_name not in servers
    servers[server_name] = server_block

    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        json.dumps(existing_config, indent=2) + "\n", encoding="utf-8"
    )

    return was_new
