from typing import Any


def drop_none(metadata: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in metadata.items() if v is not None}
