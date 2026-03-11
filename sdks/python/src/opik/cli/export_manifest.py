"""Export manifest for tracking download state and enabling resumable/incremental exports.

Uses a SQLite database (``export_manifest.db``) for atomic, efficient, crash-safe persistence.

Design mirrors MigrationManifest (used for imports):

* **WAL mode** -- SQLite Write-Ahead Log allows safe reads during writes and recovers
  cleanly from process crashes.
* **``synchronous=NORMAL``** -- with WAL this is safe against process crashes while being
  significantly faster than ``FULL``.
* **Batched writes** -- ``mark_trace_downloaded`` buffers changes in memory and commits
  every ``batch_size`` operations (default 50) in a single transaction.
* **Idempotent inserts** -- ``INSERT OR IGNORE`` means re-running after a crash produces
  no duplicates in the DB.

Lifecycle
---------
* No manifest → fresh export (full fetch, filesystem scan for skip-check).
* ``status = in_progress`` → interrupted; load downloaded IDs from DB and resume.
* ``status = completed`` → use ``last_exported_at`` to filter API to only new traces.
* ``--force`` → ``reset()`` wipes all state and starts fresh.

Crash trade-off
---------------
Writes within a batch are buffered in memory. If the process is killed between automatic
flushes, up to ``batch_size - 1`` trace completions may be absent from the manifest on the
next run. Those traces will be re-downloaded (safe -- the file just gets overwritten).
``__del__`` provides a best-effort flush on normal process exit.
"""

import json
import logging
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional, Set

LOGGER = logging.getLogger(__name__)

EXPORT_MANIFEST_FILENAME = "export_manifest.db"

DEFAULT_BATCH_SIZE = 50

_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS status (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS downloaded_traces (
    trace_id      TEXT PRIMARY KEY,
    downloaded_at TEXT NOT NULL
);
"""


class ExportManifest:
    """Tracks export progress so interrupted exports resume and completed exports
    only fetch traces newer than the last run.

    Parameters
    ----------
    project_dir:
        Directory where trace files are stored.  The ``export_manifest.db`` file
        is created here alongside the trace files.
    batch_size:
        Number of pending writes before an automatic flush to disk.
    """

    def __init__(
        self,
        project_dir: Path,
        batch_size: int = DEFAULT_BATCH_SIZE,
        filename: str = EXPORT_MANIFEST_FILENAME,
    ) -> None:
        self.project_dir = project_dir
        self.manifest_file = project_dir / filename
        self._batch_size = batch_size
        self._pending_downloaded: Set[str] = set()
        self._conn = self._open()

    def _open(self) -> sqlite3.Connection:
        self.project_dir.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(
            str(self.manifest_file),
            isolation_level=None,
            check_same_thread=False,
        )
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.executescript(_SCHEMA_SQL)
        return conn

    @classmethod
    def exists(cls, project_dir: Path) -> bool:
        return (project_dir / EXPORT_MANIFEST_FILENAME).exists()

    # ------------------------------------------------------------------
    # Status
    # ------------------------------------------------------------------

    @property
    def status(self) -> str:
        row = self._conn.execute(
            "SELECT value FROM status WHERE key = 'status'"
        ).fetchone()
        return row[0] if row else "not_started"

    @property
    def is_in_progress(self) -> bool:
        return self.status == "in_progress"

    @property
    def is_completed(self) -> bool:
        return self.status == "completed"

    def get_format(self) -> Optional[str]:
        """Return the format recorded for this manifest (json or csv), or None."""
        row = self._conn.execute(
            "SELECT value FROM status WHERE key = 'format'"
        ).fetchone()
        return row[0] if row else None

    def get_last_exported_at(self) -> Optional[str]:
        """Return the ISO timestamp recorded at the start of the last completed export.

        Only set when ``status = completed``.  Used to build an incremental filter
        so the next run fetches only traces created after this point.
        """
        row = self._conn.execute(
            "SELECT value FROM status WHERE key = 'last_exported_at'"
        ).fetchone()
        return row[0] if row else None

    def start(self, format: str) -> None:
        """Mark the export as in-progress (idempotent on resume)."""
        self._flush()
        with self._conn:
            self._conn.execute(
                "INSERT OR IGNORE INTO status(key, value) VALUES ('started_at', ?)",
                (datetime.now(timezone.utc).isoformat(),),
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('status', 'in_progress')",
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('format', ?)",
                (format,),
            )

    def complete(self, export_start_time: str) -> None:
        """Mark the export as fully completed.

        ``export_start_time`` (ISO string, recorded before the first API call) is
        stored as ``last_exported_at`` so the next run can use it as an incremental
        cutoff.  Using the start time (rather than the completion time) ensures that
        any trace created while the export was running is included in the next run's
        window.
        """
        self._flush()
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('status', 'completed')",
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('completed_at', ?)",
                (datetime.now(timezone.utc).isoformat(),),
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('last_exported_at', ?)",
                (export_start_time,),
            )

    def reset(self) -> None:
        """Discard all tracked state (used by ``--force``)."""
        self._pending_downloaded.clear()
        with self._conn:
            self._conn.execute("DELETE FROM status")
            self._conn.execute("DELETE FROM downloaded_traces")

    # ------------------------------------------------------------------
    # Trace tracking
    # ------------------------------------------------------------------

    def is_trace_downloaded(self, trace_id: str) -> bool:
        """Return True if this trace ID is in the downloaded set (buffer or DB)."""
        if trace_id in self._pending_downloaded:
            return True
        row = self._conn.execute(
            "SELECT 1 FROM downloaded_traces WHERE trace_id = ?", (trace_id,)
        ).fetchone()
        return row is not None

    def mark_trace_downloaded(self, trace_id: str) -> None:
        """Record that a trace has been written to disk (batched)."""
        self._pending_downloaded.add(trace_id)
        self._maybe_flush()

    def load_downloaded_set(self) -> Set[str]:
        """Return all downloaded trace IDs as a Python set for O(1) lookups.

        Flushes any pending writes first so the set is complete.
        """
        self._flush()
        rows = self._conn.execute("SELECT trace_id FROM downloaded_traces").fetchall()
        return {row[0] for row in rows}

    def downloaded_count(self) -> int:
        self._flush()
        row = self._conn.execute("SELECT COUNT(*) FROM downloaded_traces").fetchone()
        return row[0] if row else 0

    # ------------------------------------------------------------------
    # Trace ID list (used by experiment manifests)
    # ------------------------------------------------------------------

    def store_all_trace_ids(self, trace_ids: List[str]) -> None:
        """Store the complete list of trace IDs for this export.

        Used by experiment exports so that a completed-manifest re-run can
        return the known trace IDs without calling ``experiment.get_items()``.
        """
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('all_trace_ids', ?)",
                (json.dumps(trace_ids),),
            )

    def get_all_trace_ids(self) -> Optional[List[str]]:
        """Return the stored list of all trace IDs, or None if not yet stored."""
        row = self._conn.execute(
            "SELECT value FROM status WHERE key = 'all_trace_ids'"
        ).fetchone()
        return json.loads(row[0]) if row else None

    # ------------------------------------------------------------------
    # Lifecycle helpers
    # ------------------------------------------------------------------

    def save(self) -> None:
        """Flush any buffered writes to disk immediately."""
        self._flush()

    def close(self) -> None:
        """Flush pending writes and release the DB connection."""
        try:
            self._flush()
        finally:
            self._conn.close()

    def __del__(self) -> None:
        """Best-effort flush when the object is garbage-collected."""
        try:
            self._flush()
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    @property
    def _pending_count(self) -> int:
        return len(self._pending_downloaded)

    def _maybe_flush(self) -> None:
        if self._pending_count >= self._batch_size:
            self._flush()

    def _flush(self) -> None:
        """Commit all buffered writes in a single atomic transaction."""
        if self._pending_count == 0:
            return
        now = datetime.now(timezone.utc).isoformat()
        with self._conn:
            self._conn.executemany(
                "INSERT OR IGNORE INTO downloaded_traces(trace_id, downloaded_at) VALUES (?, ?)",
                [(tid, now) for tid in self._pending_downloaded],
            )
            self._pending_downloaded.clear()
