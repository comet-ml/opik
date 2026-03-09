"""Migration manifest for tracking import state and enabling resumable migrations.

Uses a SQLite database (``migration_manifest.db``) for atomic, efficient,
crash-safe persistence.

Design
------
* **WAL mode** – SQLite's Write-Ahead Log allows safe, concurrent reads
  during writes and recovers cleanly from process crashes.
* **``synchronous=NORMAL``** – with WAL this is safe against process crashes
  (data survives) while being significantly faster than ``FULL``.
* **Batched writes** – ``mark_file_completed``, ``mark_file_failed``, and
  ``add_trace_mapping`` buffer changes in memory and commit every
  ``batch_size`` operations (default 50) in a single transaction.
  Lifecycle calls (``start``, ``complete``, ``reset``) always flush
  immediately so that import state is never stale on disk.
* **Idempotent writes** – ``INSERT OR IGNORE`` / ``INSERT OR REPLACE``
  mean re-running after a crash produces no duplicates in the DB.
* **In-memory deduplication** – pending buffers use ``set``/``dict`` so
  duplicate calls within a batch are collapsed before hitting SQLite.

Crash trade-off
---------------
Writes within a batch are buffered in memory. If the process is killed
between automatic flushes, up to ``batch_size - 1`` file completions may be
absent from the manifest on the next run. Those files will be re-imported
(idempotent for datasets/prompts; may create a duplicate trace — see the
project import docs). ``__del__`` provides a best-effort flush on normal
process exit. Use ``batch_size=1`` for per-file durability at the cost of
more disk transactions.

Lifecycle
---------
* No manifest → fresh import; ``MigrationManifest`` should only be
  constructed when a real import is about to start (not during ``--dry-run``).
* ``status = in_progress`` → interrupted; resume skips completed files.
* ``status = completed`` → nothing re-imported unless ``--force`` resets.
* ``--force`` → ``reset()`` wipes all tables and starts fresh.
"""

import logging
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Set

LOGGER = logging.getLogger(__name__)

MANIFEST_FILENAME = "migration_manifest.db"


# Number of pending write operations that triggers an automatic flush to disk.
# Valid range: 1–10000 entries per SQLite transaction commit.
# Override with the OPIK_MIGRATION_BATCH_SIZE environment variable.
def _resolve_batch_size() -> int:
    raw = os.getenv("OPIK_MIGRATION_BATCH_SIZE", "")
    if raw:
        try:
            val = int(raw)
            if 1 <= val <= 10000:
                return val
            LOGGER.warning(
                "OPIK_MIGRATION_BATCH_SIZE=%r is outside the valid range 1–10000; "
                "using default of 50.",
                raw,
            )
        except ValueError:
            LOGGER.warning(
                "OPIK_MIGRATION_BATCH_SIZE=%r is not a valid integer; "
                "using default of 50.",
                raw,
            )
    return 50


DEFAULT_BATCH_SIZE: int = _resolve_batch_size()

_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS status (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS completed_files (
    path TEXT PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS failed_files (
    path  TEXT PRIMARY KEY,
    error TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS trace_id_map (
    src_id  TEXT PRIMARY KEY,
    dest_id TEXT NOT NULL
);
"""


class MigrationManifest:
    """Tracks import progress so interrupted migrations can be resumed safely.

    Parameters
    ----------
    base_path:
        Root directory of the export tree.  The ``migration_manifest.db``
        file is created here.
    batch_size:
        Number of pending write operations that triggers an automatic flush
        to disk.  Larger values mean fewer disk transactions (faster) but
        more work potentially lost if the process is killed between flushes.
        Defaults to ``DEFAULT_BATCH_SIZE`` (50).
    """

    def __init__(self, base_path: Path, batch_size: int = DEFAULT_BATCH_SIZE) -> None:
        self.base_path = base_path
        self.manifest_file = base_path / MANIFEST_FILENAME
        self._batch_size = batch_size

        # In-memory write buffers — flushed in one transaction per batch.
        # Using set/dict collapses duplicates before they reach SQLite.
        self._pending_completed: Set[str] = set()
        self._pending_failed: Dict[str, str] = {}  # path -> error
        self._pending_trace_map: Dict[str, str] = {}  # src_id -> dest_id

        self._conn = self._open()

    # ------------------------------------------------------------------
    # Construction helpers
    # ------------------------------------------------------------------

    def _open(self) -> sqlite3.Connection:
        self.base_path.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(
            str(self.manifest_file),
            # autocommit mode — we manage every transaction explicitly with
            # "with conn:" so there are no implicit BEGIN surprises.
            isolation_level=None,
            check_same_thread=True,
        )
        # WAL: persistent per-file setting, safe to set on every open.
        conn.execute("PRAGMA journal_mode=WAL")
        # NORMAL sync: per-connection setting, must be applied each open.
        conn.execute("PRAGMA synchronous=NORMAL")
        # Create tables if they don't exist yet.
        # executescript always commits first; fine here since we just opened.
        conn.executescript(_SCHEMA_SQL)
        return conn

    @classmethod
    def exists(cls, base_path: Path) -> bool:
        return (base_path / MANIFEST_FILENAME).exists()

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

    def start(self) -> None:
        """Mark the import as in-progress (idempotent on resume)."""
        self._flush()
        with self._conn:
            # Preserve the original started_at across resume calls.
            self._conn.execute(
                "INSERT OR IGNORE INTO status(key, value) VALUES ('started_at', ?)",
                (datetime.now(timezone.utc).isoformat(),),
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('status', 'in_progress')",
            )

    def complete(self) -> None:
        """Mark the import as fully completed, flushing any buffered writes."""
        self._flush()
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('status', 'completed')",
            )
            self._conn.execute(
                "INSERT OR REPLACE INTO status(key, value) VALUES ('completed_at', ?)",
                (datetime.now(timezone.utc).isoformat(),),
            )

    def reset(self) -> None:
        """Discard all tracked state and start fresh (used by ``--force``)."""
        # Drop buffered writes — they belong to the old run.
        self._pending_completed.clear()
        self._pending_failed.clear()
        self._pending_trace_map.clear()
        with self._conn:
            self._conn.execute("DELETE FROM status")
            self._conn.execute("DELETE FROM completed_files")
            self._conn.execute("DELETE FROM failed_files")
            self._conn.execute("DELETE FROM trace_id_map")

    # ------------------------------------------------------------------
    # File-level tracking
    # ------------------------------------------------------------------

    def relative_path(self, file_path: Path) -> str:
        """Return the canonical relative path string used as a manifest key."""
        return str(file_path.relative_to(self.base_path))

    def is_file_completed(self, file_path: Path) -> bool:
        rel = self.relative_path(file_path)
        # Check the in-memory buffer first (O(1)); avoids a DB round-trip for
        # files that were completed in the current batch but not yet flushed.
        if rel in self._pending_completed:
            return True
        row = self._conn.execute(
            "SELECT 1 FROM completed_files WHERE path = ?", (rel,)
        ).fetchone()
        return row is not None

    def mark_file_completed(self, file_path: Path) -> None:
        rel = self.relative_path(file_path)
        self._pending_completed.add(rel)
        # Completion supersedes any earlier failure recorded in the buffer.
        self._pending_failed.pop(rel, None)
        self._maybe_flush()

    def mark_file_failed(self, file_path: Path, error: str) -> None:
        rel = self.relative_path(file_path)
        self._pending_failed[rel] = error
        self._maybe_flush()

    # ------------------------------------------------------------------
    # Trace ID mapping (src_id -> dest_id)
    # Persisted so experiment cross-references survive resume across sessions.
    # ------------------------------------------------------------------

    def add_trace_mapping(self, src_id: str, dest_id: str) -> None:
        self._pending_trace_map[src_id] = dest_id
        self._maybe_flush()

    def get_trace_id_map(self) -> Dict[str, str]:
        """Return the full trace ID mapping including any unflushed entries."""
        self._flush()
        rows = self._conn.execute("SELECT src_id, dest_id FROM trace_id_map").fetchall()
        return {src: dest for src, dest in rows}

    # ------------------------------------------------------------------
    # Public flush
    # ------------------------------------------------------------------

    def save(self) -> None:
        """Flush any buffered writes to disk immediately."""
        self._flush()

    # ------------------------------------------------------------------
    # Stats
    # ------------------------------------------------------------------

    def completed_count(self) -> int:
        self._flush()
        row = self._conn.execute("SELECT COUNT(*) FROM completed_files").fetchone()
        return row[0] if row else 0

    def failed_count(self) -> int:
        self._flush()
        row = self._conn.execute("SELECT COUNT(*) FROM failed_files").fetchone()
        return row[0] if row else 0

    def get_failed_files(self) -> Dict[str, str]:
        self._flush()
        rows = self._conn.execute("SELECT path, error FROM failed_files").fetchall()
        return {path: error for path, error in rows}

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

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
        return (
            len(self._pending_completed)
            + len(self._pending_failed)
            + len(self._pending_trace_map)
        )

    def _maybe_flush(self) -> None:
        if self._pending_count >= self._batch_size:
            self._flush()

    def _flush(self) -> None:
        """Commit all buffered writes in a single atomic transaction."""
        if self._pending_count == 0:
            return

        with self._conn:
            if self._pending_completed:
                self._conn.executemany(
                    "INSERT OR IGNORE INTO completed_files(path) VALUES (?)",
                    [(p,) for p in self._pending_completed],
                )
                # Remove from failed_files if a previous run recorded a failure.
                self._conn.executemany(
                    "DELETE FROM failed_files WHERE path = ?",
                    [(p,) for p in self._pending_completed],
                )
                self._pending_completed.clear()

            if self._pending_failed:
                self._conn.executemany(
                    "INSERT OR REPLACE INTO failed_files(path, error) VALUES (?, ?)",
                    list(self._pending_failed.items()),
                )
                self._pending_failed.clear()

            if self._pending_trace_map:
                self._conn.executemany(
                    "INSERT OR REPLACE INTO trace_id_map(src_id, dest_id) VALUES (?, ?)",
                    list(self._pending_trace_map.items()),
                )
                self._pending_trace_map.clear()
