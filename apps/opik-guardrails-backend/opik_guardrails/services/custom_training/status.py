import json
import os
import tempfile
from typing import Any, Dict


def read(status_path: str) -> Dict[str, Any]:
    if not os.path.isfile(status_path):
        return {}
    with open(status_path) as f:
        return json.load(f)


def write(status_path: str, status: str, **extra: Any) -> None:
    """Write status atomically so a concurrent reader never sees a torn file."""
    payload = {"status": status, **extra}
    directory = os.path.dirname(status_path)
    fd, tmp_path = tempfile.mkstemp(dir=directory, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(payload, f)
        os.replace(tmp_path, status_path)
    except BaseException:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        raise
