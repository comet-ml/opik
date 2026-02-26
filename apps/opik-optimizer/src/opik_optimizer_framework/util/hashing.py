import hashlib
import json
from typing import Any


def canonical_config_hash(config: dict[str, Any]) -> str:
    """Deterministic hash of a candidate configuration for deduplication.

    JSON-serializes with sorted keys and compact separators, then
    SHA256-hashes and truncates to 16 hex characters.
    """
    serialized = json.dumps(config, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()[:16]
