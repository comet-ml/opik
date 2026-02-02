"""SQLite-based config store with append-only history and pointer semantics."""

from __future__ import annotations

import hashlib
import json
import random
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock
from typing import Any, Generator

# Word lists for generating fun experiment names
ADJECTIVES = [
    "happy", "silly", "brave", "clever", "swift", "calm", "bright", "bold",
    "eager", "fancy", "gentle", "jolly", "kind", "lively", "merry", "noble",
    "proud", "quick", "sharp", "witty", "zesty", "cosmic", "stellar", "lunar",
    "golden", "silver", "crystal", "mystic", "ancient", "modern", "digital",
]

NOUNS = [
    "falcon", "tiger", "dolphin", "phoenix", "dragon", "panda", "koala", "owl",
    "hawk", "wolf", "bear", "fox", "lion", "eagle", "raven", "otter", "badger",
    "rocket", "comet", "nebula", "galaxy", "quasar", "photon", "neutron",
    "pixel", "vector", "tensor", "matrix", "cipher", "quantum", "prism",
]


def generate_experiment_name() -> str:
    """Generate a fun experiment name like 'happy-falcon-4821'."""
    adj = random.choice(ADJECTIVES)
    noun = random.choice(NOUNS)
    num = random.randint(1000, 9999)
    return f"{adj}-{noun}-{num}"


@dataclass
class ResolveResult:
    """Result from resolving config keys."""
    resolved_values: dict[str, Any] = field(default_factory=dict)
    resolved_value_ids: dict[str, int] = field(default_factory=dict)
    missing_keys: list[str] = field(default_factory=list)
    assigned_variant: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "resolved_values": self.resolved_values,
            "resolved_value_ids": self.resolved_value_ids,
            "missing_keys": self.missing_keys,
            "assigned_variant": self.assigned_variant,
        }


class SQLiteConfigStore:
    """SQLite-backed config store with append-only values and pointer tables."""

    def __init__(self, db_path: str | Path = ":memory:") -> None:
        self.db_path = str(db_path)
        self._lock = Lock()
        # For in-memory databases, keep a persistent connection
        self._conn: sqlite3.Connection | None = None
        if self.db_path == ":memory:":
            self._conn = self._create_connection()
        self._init_db()

    def _create_connection(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        return conn

    @contextmanager
    def _transaction(self) -> Generator[sqlite3.Connection, None, None]:
        with self._lock:
            # For in-memory, use persistent connection; for file, create new
            if self._conn is not None:
                conn = self._conn
                close_after = False
            else:
                conn = self._create_connection()
                close_after = True
            try:
                yield conn
                conn.commit()
            except Exception:
                conn.rollback()
                raise
            finally:
                if close_after:
                    conn.close()

    def _init_db(self) -> None:
        with self._transaction() as conn:
            conn.executescript("""
                -- Tracks discovered keys for a project
                CREATE TABLE IF NOT EXISTS config_keys (
                    id INTEGER PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    key TEXT NOT NULL,
                    type TEXT NULL,
                    default_hash TEXT NULL,
                    source_json TEXT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(project_id, key)
                );

                -- Append-only history of values for keys
                CREATE TABLE IF NOT EXISTS config_values (
                    id INTEGER PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    key_id INTEGER NOT NULL REFERENCES config_keys(id),
                    value_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    created_by TEXT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_config_values_lookup
                    ON config_values(project_id, key_id, created_at);

                -- Pointer to the current published value per env+key
                CREATE TABLE IF NOT EXISTS config_published (
                    project_id TEXT NOT NULL,
                    env TEXT NOT NULL,
                    key_id INTEGER NOT NULL REFERENCES config_keys(id),
                    value_id INTEGER NOT NULL REFERENCES config_values(id),
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(project_id, env, key_id)
                );

                -- Defines an experiment/mask
                CREATE TABLE IF NOT EXISTS masks (
                    id INTEGER PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    env TEXT NOT NULL,
                    mask_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    is_ab INTEGER NOT NULL DEFAULT 0,
                    salt TEXT NOT NULL,
                    distribution_json TEXT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(project_id, env, mask_id)
                );

                -- Override pointers per variant+key
                CREATE TABLE IF NOT EXISTS mask_overrides (
                    project_id TEXT NOT NULL,
                    env TEXT NOT NULL,
                    mask_id TEXT NOT NULL,
                    variant TEXT NOT NULL,
                    key_id INTEGER NOT NULL REFERENCES config_keys(id),
                    value_id INTEGER NOT NULL REFERENCES config_values(id),
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(project_id, env, mask_id, variant, key_id)
                );
            """)

            # Migration: add name column to masks if it doesn't exist
            cursor = conn.execute("PRAGMA table_info(masks)")
            columns = {row[1] for row in cursor.fetchall()}
            if "name" not in columns:
                conn.execute("ALTER TABLE masks ADD COLUMN name TEXT")
                # Backfill existing rows with generated names
                rows = conn.execute("SELECT id, mask_id FROM masks WHERE name IS NULL").fetchall()
                for row in rows:
                    conn.execute(
                        "UPDATE masks SET name = ? WHERE id = ?",
                        (generate_experiment_name(), row[0])
                    )

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def _ensure_key(self, conn: sqlite3.Connection, project_id: str, key: str) -> int:
        """Ensure a key exists and return its id."""
        now = self._now()
        conn.execute("""
            INSERT INTO config_keys (project_id, key, created_at, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(project_id, key) DO UPDATE SET updated_at = ?
        """, (project_id, key, now, now, now))

        row = conn.execute(
            "SELECT id FROM config_keys WHERE project_id = ? AND key = ?",
            (project_id, key)
        ).fetchone()
        return row["id"]

    def _insert_value(
        self,
        conn: sqlite3.Connection,
        project_id: str,
        key_id: int,
        value: Any,
        created_by: str | None = None,
    ) -> int:
        """Insert a new value and return its id."""
        now = self._now()
        value_json = json.dumps(value)
        cursor = conn.execute("""
            INSERT INTO config_values (project_id, key_id, value_json, created_at, created_by)
            VALUES (?, ?, ?, ?, ?)
        """, (project_id, key_id, value_json, now, created_by))
        return cursor.lastrowid  # type: ignore

    def _compute_variant(
        self,
        mask_id: str,
        salt: str,
        unit_id: str | None,
        distribution: dict[str, int],
    ) -> str:
        """Compute deterministic variant assignment using hash bucketing."""
        if not unit_id:
            # Default to first variant (usually "A") when no unit_id
            return next(iter(distribution.keys())) if distribution else "A"

        # Hash to get a bucket number 0-9999
        hash_input = f"{mask_id}:{salt}:{unit_id}"
        h = hashlib.sha256(hash_input.encode()).hexdigest()
        bucket = int(h[:8], 16) % 10000

        # Map bucket to variant based on cumulative distribution
        cumulative = 0
        for variant, weight in distribution.items():
            # Weight is percentage (0-100), scale to 0-10000
            cumulative += weight * 100
            if bucket < cumulative:
                return variant

        # Fallback to last variant
        return list(distribution.keys())[-1] if distribution else "A"

    # =========================================================================
    # Public API
    # =========================================================================

    def resolve(
        self,
        project_id: str,
        env: str,
        keys: list[str],
        mask_id: str | None = None,
        unit_id: str | None = None,
    ) -> ResolveResult:
        """
        Resolve config values for the given keys.

        Precedence:
        1. If mask_id provided: check mask override for (mask_id, variant, key)
        2. Fall back to published value for (env, key)
        3. If neither exists: key is missing
        """
        result = ResolveResult()
        assigned_variant: str | None = None

        with self._transaction() as conn:
            # If mask_id provided, look up mask and compute variant
            mask_row = None
            if mask_id:
                mask_row = conn.execute("""
                    SELECT id, is_ab, salt, distribution_json
                    FROM masks
                    WHERE project_id = ? AND env = ? AND mask_id = ?
                """, (project_id, env, mask_id)).fetchone()

                if mask_row and mask_row["is_ab"]:
                    distribution = json.loads(mask_row["distribution_json"] or '{"A":50,"B":50}')
                    assigned_variant = self._compute_variant(
                        mask_id, mask_row["salt"], unit_id, distribution
                    )
                elif mask_row:
                    assigned_variant = "default"

            result.assigned_variant = assigned_variant

            for key in keys:
                # Get key_id if it exists
                key_row = conn.execute(
                    "SELECT id FROM config_keys WHERE project_id = ? AND key = ?",
                    (project_id, key)
                ).fetchone()

                if not key_row:
                    result.missing_keys.append(key)
                    continue

                key_id = key_row["id"]
                value_id: int | None = None
                value_json: str | None = None

                # 1. Check mask override if mask_id and variant are known
                if mask_id and assigned_variant:
                    override = conn.execute("""
                        SELECT mo.value_id, cv.value_json
                        FROM mask_overrides mo
                        JOIN config_values cv ON cv.id = mo.value_id
                        WHERE mo.project_id = ? AND mo.env = ? AND mo.mask_id = ?
                          AND mo.variant = ? AND mo.key_id = ?
                    """, (project_id, env, mask_id, assigned_variant, key_id)).fetchone()

                    if override:
                        value_id = override["value_id"]
                        value_json = override["value_json"]

                # 2. Fall back to published value
                if value_id is None:
                    published = conn.execute("""
                        SELECT cp.value_id, cv.value_json
                        FROM config_published cp
                        JOIN config_values cv ON cv.id = cp.value_id
                        WHERE cp.project_id = ? AND cp.env = ? AND cp.key_id = ?
                    """, (project_id, env, key_id)).fetchone()

                    if published:
                        value_id = published["value_id"]
                        value_json = published["value_json"]

                # 3. If still no value, key is missing
                if value_id is None:
                    result.missing_keys.append(key)
                else:
                    result.resolved_values[key] = json.loads(value_json)  # type: ignore
                    result.resolved_value_ids[key] = value_id

        return result

    def register_keys(
        self,
        project_id: str,
        keys: list[dict[str, Any]],
        env: str = "prod",
    ) -> None:
        """
        Register discovered keys (best-effort).
        Also publishes default values if not already published.

        Each key dict may contain:
        - key: str (required)
        - type: str (optional)
        - default_value: any (optional, will be published if no value exists)
        - source: dict (optional, stored as JSON)
        """
        now = self._now()

        # Collect keys that need defaults published (do outside transaction to avoid deadlock)
        keys_to_publish: list[tuple[str, Any]] = []

        with self._transaction() as conn:
            for key_data in keys:
                key = key_data.get("key")
                if not key:
                    continue

                type_hint = key_data.get("type")
                source_json = json.dumps(key_data.get("source")) if key_data.get("source") else None

                conn.execute("""
                    INSERT INTO config_keys (project_id, key, type, default_hash, source_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(project_id, key) DO UPDATE SET
                        type = COALESCE(excluded.type, config_keys.type),
                        source_json = COALESCE(excluded.source_json, config_keys.source_json),
                        updated_at = excluded.updated_at
                """, (project_id, key, type_hint, None, source_json, now, now))

                # Check if we need to publish a default
                if "default_value" in key_data:
                    key_row = conn.execute(
                        "SELECT id FROM config_keys WHERE project_id = ? AND key = ?",
                        (project_id, key)
                    ).fetchone()

                    if key_row:
                        # Check if already published
                        published = conn.execute(
                            "SELECT 1 FROM config_published WHERE project_id = ? AND env = ? AND key_id = ?",
                            (project_id, env, key_row["id"])
                        ).fetchone()

                        if not published:
                            keys_to_publish.append((key, key_data["default_value"]))

        # Publish defaults outside the main transaction
        for key, default_value in keys_to_publish:
            self.publish_value(project_id, env, key, default_value, created_by="default")

    def publish_value(
        self,
        project_id: str,
        env: str,
        key: str,
        value: Any,
        created_by: str | None = None,
    ) -> int:
        """
        Publish a value for a key in an environment.

        - Ensures key exists
        - Appends value to config_values
        - Updates config_published pointer

        Returns the value_id.
        """
        with self._transaction() as conn:
            key_id = self._ensure_key(conn, project_id, key)
            value_id = self._insert_value(conn, project_id, key_id, value, created_by)

            now = self._now()
            conn.execute("""
                INSERT INTO config_published (project_id, env, key_id, value_id, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(project_id, env, key_id) DO UPDATE SET
                    value_id = excluded.value_id,
                    updated_at = excluded.updated_at
            """, (project_id, env, key_id, value_id, now))

            return value_id

    def create_or_update_mask(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        name: str | None = None,
        is_ab: bool = False,
        distribution: dict[str, int] | None = None,
        salt: str | None = None,
    ) -> str:
        """
        Create or update a mask/experiment.

        Args:
            project_id: Project identifier
            env: Environment (e.g., "prod", "staging")
            mask_id: External stable identifier for the mask
            name: Human-readable name (auto-generated if not provided)
            is_ab: Whether this is an A/B test
            distribution: Variant weights, e.g. {"A": 50, "B": 50}
            salt: Salt for hashing (auto-generated if not provided)

        Returns:
            The experiment name (generated or provided)
        """
        if salt is None:
            salt = hashlib.sha256(f"{project_id}:{env}:{mask_id}".encode()).hexdigest()[:16]

        if name is None:
            name = generate_experiment_name()

        distribution_json = json.dumps(distribution) if distribution else None

        now = self._now()
        with self._transaction() as conn:
            conn.execute("""
                INSERT INTO masks (project_id, env, mask_id, name, is_ab, salt, distribution_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, env, mask_id) DO UPDATE SET
                    name = COALESCE(excluded.name, masks.name),
                    is_ab = excluded.is_ab,
                    salt = excluded.salt,
                    distribution_json = excluded.distribution_json,
                    updated_at = excluded.updated_at
            """, (project_id, env, mask_id, name, int(is_ab), salt, distribution_json, now, now))

        return name

    def set_mask_override(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        variant: str,
        key: str,
        value: Any,
        created_by: str | None = None,
    ) -> int:
        """
        Set an override value for a specific variant of a mask.

        Returns the value_id.
        """
        with self._transaction() as conn:
            key_id = self._ensure_key(conn, project_id, key)
            value_id = self._insert_value(conn, project_id, key_id, value, created_by)

            now = self._now()
            conn.execute("""
                INSERT INTO mask_overrides (project_id, env, mask_id, variant, key_id, value_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, env, mask_id, variant, key_id) DO UPDATE SET
                    value_id = excluded.value_id,
                    updated_at = excluded.updated_at
            """, (project_id, env, mask_id, variant, key_id, value_id, now))

            return value_id

    # =========================================================================
    # Query helpers for UI/debugging
    # =========================================================================

    def list_keys(self, project_id: str) -> list[dict[str, Any]]:
        """List all registered keys for a project."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT key, type, default_hash, source_json, created_at, updated_at
                FROM config_keys
                WHERE project_id = ?
                ORDER BY key
            """, (project_id,)).fetchall()

            return [
                {
                    "key": row["key"],
                    "type": row["type"],
                    "default_hash": row["default_hash"],
                    "source": json.loads(row["source_json"]) if row["source_json"] else None,
                    "created_at": row["created_at"],
                    "updated_at": row["updated_at"],
                }
                for row in rows
            ]

    def find_key_by_prompt_name(self, project_id: str, env: str, prompt_name: str) -> str | None:
        """
        Find the config key that has a prompt with the given name.

        Searches published values for prompts where prompt_name matches.
        Returns the key string (e.g., "AgentConfig.researcher_system_prompt") or None.
        """
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT ck.key, cv.value_json
                FROM config_published cp
                JOIN config_keys ck ON ck.id = cp.key_id
                JOIN config_values cv ON cv.id = cp.value_id
                WHERE cp.project_id = ? AND cp.env = ?
            """, (project_id, env)).fetchall()

            for row in rows:
                try:
                    value = json.loads(row["value_json"])
                    if isinstance(value, dict) and value.get("prompt_name") == prompt_name:
                        return row["key"]
                except (json.JSONDecodeError, TypeError):
                    continue

            return None

    def list_published(self, project_id: str, env: str) -> list[dict[str, Any]]:
        """List all published values for an environment."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT ck.key, cv.value_json, cp.value_id, cp.updated_at
                FROM config_published cp
                JOIN config_keys ck ON ck.id = cp.key_id
                JOIN config_values cv ON cv.id = cp.value_id
                WHERE cp.project_id = ? AND cp.env = ?
                ORDER BY ck.key
            """, (project_id, env)).fetchall()

            return [
                {
                    "key": row["key"],
                    "value": json.loads(row["value_json"]),
                    "value_id": row["value_id"],
                    "updated_at": row["updated_at"],
                }
                for row in rows
            ]

    def list_masks(self, project_id: str, env: str) -> list[dict[str, Any]]:
        """List all masks for an environment."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT mask_id, name, is_ab, salt, distribution_json, created_at, updated_at
                FROM masks
                WHERE project_id = ? AND env = ?
                ORDER BY created_at DESC
            """, (project_id, env)).fetchall()

            return [
                {
                    "mask_id": row["mask_id"],
                    "name": row["name"],
                    "is_ab": bool(row["is_ab"]),
                    "salt": row["salt"],
                    "distribution": json.loads(row["distribution_json"]) if row["distribution_json"] else None,
                    "created_at": row["created_at"],
                    "updated_at": row["updated_at"],
                }
                for row in rows
            ]

    def list_mask_overrides(self, project_id: str, env: str, mask_id: str) -> list[dict[str, Any]]:
        """List all overrides for a specific mask."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT mo.variant, ck.key, cv.value_json, mo.value_id, mo.updated_at
                FROM mask_overrides mo
                JOIN config_keys ck ON ck.id = mo.key_id
                JOIN config_values cv ON cv.id = mo.value_id
                WHERE mo.project_id = ? AND mo.env = ? AND mo.mask_id = ?
                ORDER BY mo.variant, ck.key
            """, (project_id, env, mask_id)).fetchall()

            return [
                {
                    "variant": row["variant"],
                    "key": row["key"],
                    "value": json.loads(row["value_json"]),
                    "value_id": row["value_id"],
                    "updated_at": row["updated_at"],
                }
                for row in rows
            ]

    def get_value_history(self, project_id: str, key: str, limit: int = 100) -> list[dict[str, Any]]:
        """Get value history for a key."""
        with self._transaction() as conn:
            key_row = conn.execute(
                "SELECT id FROM config_keys WHERE project_id = ? AND key = ?",
                (project_id, key)
            ).fetchone()

            if not key_row:
                return []

            rows = conn.execute("""
                SELECT id, value_json, created_at, created_by
                FROM config_values
                WHERE project_id = ? AND key_id = ?
                ORDER BY created_at DESC
                LIMIT ?
            """, (project_id, key_row["id"], limit)).fetchall()

            return [
                {
                    "value_id": row["id"],
                    "value": json.loads(row["value_json"]),
                    "created_at": row["created_at"],
                    "created_by": row["created_by"],
                }
                for row in rows
            ]

    def dump_all_tables(self) -> dict[str, Any]:
        """Dump all table contents for debugging."""
        with self._transaction() as conn:
            result: dict[str, Any] = {}

            # config_keys
            rows = conn.execute("SELECT * FROM config_keys ORDER BY id").fetchall()
            result["config_keys"] = [
                {
                    "id": r["id"],
                    "project_id": r["project_id"],
                    "key": r["key"],
                    "type": r["type"],
                    "default_hash": r["default_hash"],
                    "source_json": r["source_json"],
                    "created_at": r["created_at"],
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

            # config_values
            rows = conn.execute("""
                SELECT cv.*, ck.key as key_name
                FROM config_values cv
                JOIN config_keys ck ON ck.id = cv.key_id
                ORDER BY cv.id
            """).fetchall()
            result["config_values"] = [
                {
                    "id": r["id"],
                    "project_id": r["project_id"],
                    "key_id": r["key_id"],
                    "key_name": r["key_name"],
                    "value": json.loads(r["value_json"]),
                    "created_at": r["created_at"],
                    "created_by": r["created_by"],
                }
                for r in rows
            ]

            # config_published
            rows = conn.execute("""
                SELECT cp.*, ck.key as key_name, cv.value_json
                FROM config_published cp
                JOIN config_keys ck ON ck.id = cp.key_id
                JOIN config_values cv ON cv.id = cp.value_id
                ORDER BY cp.project_id, cp.env, ck.key
            """).fetchall()
            result["config_published"] = [
                {
                    "project_id": r["project_id"],
                    "env": r["env"],
                    "key_id": r["key_id"],
                    "key_name": r["key_name"],
                    "value_id": r["value_id"],
                    "value": json.loads(r["value_json"]),
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

            # masks
            rows = conn.execute("SELECT * FROM masks ORDER BY id").fetchall()
            result["masks"] = [
                {
                    "id": r["id"],
                    "project_id": r["project_id"],
                    "env": r["env"],
                    "mask_id": r["mask_id"],
                    "is_ab": bool(r["is_ab"]),
                    "salt": r["salt"],
                    "distribution": json.loads(r["distribution_json"]) if r["distribution_json"] else None,
                    "created_at": r["created_at"],
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

            # mask_overrides
            rows = conn.execute("""
                SELECT mo.*, ck.key as key_name, cv.value_json
                FROM mask_overrides mo
                JOIN config_keys ck ON ck.id = mo.key_id
                JOIN config_values cv ON cv.id = mo.value_id
                ORDER BY mo.project_id, mo.env, mo.mask_id, mo.variant, ck.key
            """).fetchall()
            result["mask_overrides"] = [
                {
                    "project_id": r["project_id"],
                    "env": r["env"],
                    "mask_id": r["mask_id"],
                    "variant": r["variant"],
                    "key_id": r["key_id"],
                    "key_name": r["key_name"],
                    "value_id": r["value_id"],
                    "value": json.loads(r["value_json"]),
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

            return result
