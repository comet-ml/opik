"""Migration manifest for tracking import state and enabling resumable migrations.

The manifest file (migration_manifest.json) lives at the root of the export
directory and is automatically detected when running ``opik import``.

Lifecycle:
  - If no manifest exists, a fresh one is created and import proceeds normally.
  - If a manifest with status ``in_progress`` is found, the import resumes from
    where it left off, skipping files already in ``completed_files``.
  - If a manifest with status ``completed`` is found, nothing is re-imported
    unless ``--force`` is given.
  - ``--force`` resets the manifest and starts from scratch.

The manifest also persists the trace ID mapping (source ID -> destination ID)
so that experiment cross-references can be resolved correctly when an import is
resumed across separate invocations (e.g. the user Ctrl-C'd halfway through).
"""

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict

MANIFEST_FILENAME = "migration_manifest.json"
SCHEMA_VERSION = 1


class MigrationManifest:
    """Tracks import progress so interrupted migrations can be resumed safely."""

    def __init__(self, base_path: Path) -> None:
        self.base_path = base_path
        self.manifest_file = base_path / MANIFEST_FILENAME
        self._data = self._load_or_create()

    # ------------------------------------------------------------------
    # Construction helpers
    # ------------------------------------------------------------------

    def _load_or_create(self) -> dict:
        if self.manifest_file.exists():
            with open(self.manifest_file, "r", encoding="utf-8") as f:
                return json.load(f)
        return {
            "schema_version": SCHEMA_VERSION,
            "import": {
                "status": "not_started",
                "started_at": None,
                "completed_at": None,
                "id_map": {
                    "traces": {},
                },
                "completed_files": [],
                "failed_files": {},
            },
        }

    @classmethod
    def exists(cls, base_path: Path) -> bool:
        return (base_path / MANIFEST_FILENAME).exists()

    # ------------------------------------------------------------------
    # Status
    # ------------------------------------------------------------------

    @property
    def status(self) -> str:
        return self._data["import"]["status"]

    @property
    def is_in_progress(self) -> bool:
        return self.status == "in_progress"

    @property
    def is_completed(self) -> bool:
        return self.status == "completed"

    def start(self) -> None:
        """Mark the import as in-progress (idempotent on resume)."""
        self._data["import"]["status"] = "in_progress"
        if not self._data["import"]["started_at"]:
            self._data["import"]["started_at"] = datetime.now(timezone.utc).isoformat()
        self._save()

    def complete(self) -> None:
        """Mark the import as fully completed."""
        self._data["import"]["status"] = "completed"
        self._data["import"]["completed_at"] = datetime.now(timezone.utc).isoformat()
        self._save()

    def reset(self) -> None:
        """Discard all tracked state and start fresh (used by --force)."""
        self._data = {
            "schema_version": SCHEMA_VERSION,
            "import": {
                "status": "not_started",
                "started_at": None,
                "completed_at": None,
                "id_map": {
                    "traces": {},
                },
                "completed_files": [],
                "failed_files": {},
            },
        }
        self._save()

    # ------------------------------------------------------------------
    # File-level tracking
    # ------------------------------------------------------------------

    def relative_path(self, file_path: Path) -> str:
        """Return the canonical relative path string used as a manifest key."""
        return str(file_path.relative_to(self.base_path))

    def is_file_completed(self, file_path: Path) -> bool:
        return self.relative_path(file_path) in self._data["import"]["completed_files"]

    def mark_file_completed(self, file_path: Path) -> None:
        rel = self.relative_path(file_path)
        if rel not in self._data["import"]["completed_files"]:
            self._data["import"]["completed_files"].append(rel)
        self._data["import"]["failed_files"].pop(rel, None)
        self._save()

    def mark_file_failed(self, file_path: Path, error: str) -> None:
        rel = self.relative_path(file_path)
        self._data["import"]["failed_files"][rel] = error
        self._save()

    # ------------------------------------------------------------------
    # Trace ID mapping (src_id -> dest_id)
    # Needed so experiment cross-references survive resume across sessions.
    # ------------------------------------------------------------------

    def add_trace_mapping(self, src_id: str, dest_id: str) -> None:
        self._data["import"]["id_map"]["traces"][src_id] = dest_id

    def get_trace_id_map(self) -> Dict[str, str]:
        """Return a full copy of the persisted trace ID map."""
        return dict(self._data["import"]["id_map"]["traces"])

    def save(self) -> None:
        """Flush manifest to disk (use after a batch of add_trace_mapping calls)."""
        self._save()

    # ------------------------------------------------------------------
    # Stats
    # ------------------------------------------------------------------

    def completed_count(self) -> int:
        return len(self._data["import"]["completed_files"])

    def failed_count(self) -> int:
        return len(self._data["import"]["failed_files"])

    def get_failed_files(self) -> Dict[str, str]:
        return dict(self._data["import"]["failed_files"])

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _save(self) -> None:
        """Atomically write the manifest (write to .tmp then os.replace)."""
        tmp_path = self.manifest_file.with_suffix(".tmp")
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(self._data, f, indent=2)
        os.replace(tmp_path, self.manifest_file)
