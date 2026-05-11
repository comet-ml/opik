"""JSON audit-log skeleton for ``opik migrate``.

The schema is intentionally minimal in slice 1; slices 2-4 add new ``type``
values to the ``actions`` list. ``schema_version`` only bumps if existing
fields change shape.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

SCHEMA_VERSION = 1


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class AuditLog:
    """Append-only record of planned or executed migration actions."""

    def __init__(self, command: str, args: Dict[str, Any]) -> None:
        self.command = command
        self.args = args
        self.started_at = _now_iso()
        self.finished_at: Optional[str] = None
        self.status: str = "in_progress"
        self.actions: List[Dict[str, Any]] = []

    def record(
        self,
        type: str,
        status: str,
        details: Optional[Dict[str, Any]] = None,
        error: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Append an audit-log entry.

        ``error`` is a sanitized envelope (see ``errors.safe_error_envelope``);
        passing the raw exception text would risk leaking ``ApiError`` response
        bodies or headers into a JSON artifact users may share.
        """
        entry: Dict[str, Any] = {
            "type": type,
            "status": status,
            "recorded_at": _now_iso(),
        }
        if details:
            entry.update(details)
        if error is not None:
            entry["error"] = error
        self.actions.append(entry)

    def finalize(self, status: str) -> None:
        self.status = status
        self.finished_at = _now_iso()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "command": self.command,
            "args": self.args,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "status": self.status,
            "actions": self.actions,
        }

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")


def default_audit_path() -> Path:
    """Default location: ``./opik-migrate-<UTC-timestamp>.json``."""
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return Path.cwd() / f"opik-migrate-{stamp}.json"
