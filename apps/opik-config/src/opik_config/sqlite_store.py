"""SQLite-based config store with append-only history and pointer semantics."""

from __future__ import annotations

import hashlib
import json
import random
import sqlite3
import uuid
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
    experiment_type: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "resolved_values": self.resolved_values,
            "resolved_value_ids": self.resolved_value_ids,
            "missing_keys": self.missing_keys,
            "assigned_variant": self.assigned_variant,
            "experiment_type": self.experiment_type,
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
                    annotations_json TEXT NULL,
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
                    experiment_type TEXT NULL,
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

                -- Tracks config prompt to Opik prompt version mappings
                CREATE TABLE IF NOT EXISTS prompt_mappings (
                    id INTEGER PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    config_key TEXT NOT NULL,
                    prompt_name TEXT NOT NULL,
                    opik_prompt_id TEXT,
                    latest_commit TEXT,
                    latest_opik_version_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(project_id, prompt_name)
                );

                -- Mock Opik prompts (for demo/POC without real Opik backend)
                CREATE TABLE IF NOT EXISTS mock_prompts (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    description TEXT,
                    template_structure TEXT DEFAULT 'text',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                -- Mock Opik prompt versions
                CREATE TABLE IF NOT EXISTS mock_prompt_versions (
                    id TEXT PRIMARY KEY,
                    prompt_id TEXT NOT NULL REFERENCES mock_prompts(id),
                    "commit" TEXT NOT NULL,
                    template TEXT NOT NULL,
                    metadata_json TEXT,
                    change_description TEXT,
                    created_at TEXT NOT NULL,
                    created_by TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_mock_versions_prompt
                    ON mock_prompt_versions(prompt_id, created_at DESC);

                -- Evaluation suites for regression testing
                CREATE TABLE IF NOT EXISTS eval_suites (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    project_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    created_by TEXT,
                    UNIQUE(project_id, name)
                );
                CREATE INDEX IF NOT EXISTS idx_eval_suites_project
                    ON eval_suites(project_id);

                -- Evaluation suite items (test cases)
                CREATE TABLE IF NOT EXISTS eval_suite_items (
                    id TEXT PRIMARY KEY,
                    suite_id TEXT NOT NULL REFERENCES eval_suites(id) ON DELETE CASCADE,
                    input_data_json TEXT NOT NULL,
                    assertions_json TEXT NOT NULL,
                    trace_id TEXT,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_eval_suite_items_suite
                    ON eval_suite_items(suite_id);

                -- Evaluation suite runs
                CREATE TABLE IF NOT EXISTS eval_runs (
                    id TEXT PRIMARY KEY,
                    suite_id TEXT NOT NULL REFERENCES eval_suites(id) ON DELETE CASCADE,
                    status TEXT NOT NULL DEFAULT 'pending',
                    started_at TEXT,
                    completed_at TEXT,
                    experiment_id TEXT,
                    total_items INTEGER DEFAULT 0,
                    passed_items INTEGER DEFAULT 0,
                    pass_rate REAL DEFAULT 0.0,
                    created_at TEXT NOT NULL,
                    created_by TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_eval_runs_suite
                    ON eval_runs(suite_id, created_at DESC);

                -- Per-item results within a run
                CREATE TABLE IF NOT EXISTS eval_run_results (
                    id TEXT PRIMARY KEY,
                    run_id TEXT NOT NULL REFERENCES eval_runs(id) ON DELETE CASCADE,
                    item_id TEXT NOT NULL REFERENCES eval_suite_items(id) ON DELETE CASCADE,
                    passed INTEGER NOT NULL DEFAULT 0,
                    assertion_results_json TEXT NOT NULL,
                    trace_id TEXT,
                    duration_ms INTEGER,
                    error_message TEXT,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_eval_run_results_run
                    ON eval_run_results(run_id);

                -- Blueprints (project-scoped containers for deployment versions)
                CREATE TABLE IF NOT EXISTS blueprints (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                -- Deployment versions (append-only history)
                CREATE TABLE IF NOT EXISTS deployment_versions (
                    id TEXT PRIMARY KEY,
                    blueprint_id TEXT NOT NULL REFERENCES blueprints(id),
                    version_number INTEGER NOT NULL,
                    snapshot_json TEXT NOT NULL,
                    change_summary TEXT,
                    change_type TEXT,
                    source_experiment_id TEXT,
                    created_at TEXT NOT NULL,
                    created_by TEXT,
                    UNIQUE(blueprint_id, version_number)
                );
                CREATE INDEX IF NOT EXISTS idx_deployment_versions_blueprint
                    ON deployment_versions(blueprint_id, version_number DESC);

                -- Environment pointers (DEV/PROD)
                CREATE TABLE IF NOT EXISTS environment_pointers (
                    blueprint_id TEXT NOT NULL REFERENCES blueprints(id),
                    env TEXT NOT NULL,
                    version_number INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(blueprint_id, env)
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

            # Migration: add experiment_type column to masks if it doesn't exist
            if "experiment_type" not in columns:
                conn.execute("ALTER TABLE masks ADD COLUMN experiment_type TEXT")
                # Backfill existing rows: is_ab=1 -> 'ab', is_ab=0 -> 'live'
                conn.execute("UPDATE masks SET experiment_type = 'ab' WHERE is_ab = 1")
                conn.execute("UPDATE masks SET experiment_type = 'live' WHERE is_ab = 0")

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
        2. Fall back to PROD version's snapshot (from blueprint versioning)
        3. Fall back to config_published (legacy/fallback)
        4. If none exists: key is missing
        """
        result = ResolveResult()
        assigned_variant: str | None = None

        with self._transaction() as conn:
            # If mask_id provided, look up mask and compute variant
            mask_row = None
            experiment_type: str | None = None
            if mask_id:
                mask_row = conn.execute("""
                    SELECT id, is_ab, experiment_type, salt, distribution_json
                    FROM masks
                    WHERE project_id = ? AND env = ? AND mask_id = ?
                """, (project_id, env, mask_id)).fetchone()

                if mask_row:
                    experiment_type = mask_row["experiment_type"]
                    # Use experiment_type if set, fall back to is_ab for backwards compatibility
                    is_ab_experiment = experiment_type == "ab" if experiment_type else mask_row["is_ab"]
                    if is_ab_experiment:
                        distribution = json.loads(mask_row["distribution_json"] or '{"A":50,"B":50}')
                        assigned_variant = self._compute_variant(
                            mask_id, mask_row["salt"], unit_id, distribution
                        )
                    else:
                        assigned_variant = "default"

            result.assigned_variant = assigned_variant
            result.experiment_type = experiment_type

            # Get PROD snapshot from blueprint versioning system
            prod_snapshot: dict[str, Any] | None = None
            blueprint_row = conn.execute(
                "SELECT id FROM blueprints WHERE project_id = ?",
                (project_id,)
            ).fetchone()

            if blueprint_row:
                # Get the PROD pointer (or requested env pointer)
                pointer_row = conn.execute("""
                    SELECT version_number FROM environment_pointers
                    WHERE blueprint_id = ? AND env = ?
                """, (blueprint_row["id"], env)).fetchone()

                if pointer_row:
                    # Get that version's snapshot
                    version_row = conn.execute("""
                        SELECT snapshot_json FROM deployment_versions
                        WHERE blueprint_id = ? AND version_number = ?
                    """, (blueprint_row["id"], pointer_row["version_number"])).fetchone()

                    if version_row and version_row["snapshot_json"]:
                        prod_snapshot = json.loads(version_row["snapshot_json"])

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
                value: Any | None = None

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
                        value = json.loads(override["value_json"])

                # 2. Fall back to PROD snapshot (from blueprint versioning)
                if value is None and prod_snapshot:
                    # Check prompts section first, then config section
                    if key in prod_snapshot.get("prompts", {}):
                        value = prod_snapshot["prompts"][key]
                        value_id = 0  # Snapshot doesn't track value_ids
                    elif key in prod_snapshot.get("config", {}):
                        value = prod_snapshot["config"][key]
                        value_id = 0

                # 3. Fall back to config_published (legacy/no blueprint)
                if value is None:
                    published = conn.execute("""
                        SELECT cp.value_id, cv.value_json
                        FROM config_published cp
                        JOIN config_values cv ON cv.id = cp.value_id
                        WHERE cp.project_id = ? AND cp.env = ? AND cp.key_id = ?
                    """, (project_id, env, key_id)).fetchone()

                    if published:
                        value_id = published["value_id"]
                        value = json.loads(published["value_json"])

                # 4. If still no value, key is missing
                if value is None:
                    result.missing_keys.append(key)
                else:
                    result.resolved_values[key] = value
                    if value_id is not None:
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
        Auto-creates blueprint and initial version if prompts are registered.

        Each key dict may contain:
        - key: str (required)
        - type: str (optional)
        - default_value: any (optional, will be published if no value exists)
        - source: dict (optional, stored as JSON)
        """
        now = self._now()

        # Collect keys that need defaults published (do outside transaction to avoid deadlock)
        keys_to_publish: list[tuple[str, Any]] = []
        has_prompts = False

        with self._transaction() as conn:
            for key_data in keys:
                key = key_data.get("key")
                if not key:
                    continue

                type_hint = key_data.get("type")
                source_json = json.dumps(key_data.get("source")) if key_data.get("source") else None
                annotations_json = json.dumps(key_data.get("annotations")) if key_data.get("annotations") else None

                conn.execute("""
                    INSERT INTO config_keys (project_id, key, type, default_hash, source_json, annotations_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(project_id, key) DO UPDATE SET
                        type = COALESCE(excluded.type, config_keys.type),
                        source_json = COALESCE(excluded.source_json, config_keys.source_json),
                        annotations_json = COALESCE(excluded.annotations_json, config_keys.annotations_json),
                        updated_at = excluded.updated_at
                """, (project_id, key, type_hint, None, source_json, annotations_json, now, now))

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
                            # Track if any prompts are being registered
                            if isinstance(key_data["default_value"], dict) and "prompt_name" in key_data["default_value"]:
                                has_prompts = True

        # Publish defaults outside the main transaction
        for key, default_value in keys_to_publish:
            self.publish_value(project_id, env, key, default_value, created_by="default")

        # Auto-register prompt mappings for all prompt-shaped values
        for key_data in keys:
            key = key_data.get("key")
            default_value = key_data.get("default_value")
            if key and isinstance(default_value, dict) and "prompt_name" in default_value:
                prompt_name = default_value["prompt_name"]
                self.register_prompt_mapping(
                    project_id=project_id,
                    config_key=key,
                    prompt_name=prompt_name,
                )

        # Auto-create blueprint with v1 if prompts were registered
        if has_prompts and keys_to_publish:
            self._ensure_blueprint_with_initial_version(project_id, env)

    def publish_value(
        self,
        project_id: str,
        env: str,
        key: str,
        value: Any,
        created_by: str | None = None,
        _skip_version: bool = False,
    ) -> int:
        """
        Publish a value for a key in an environment.

        - Ensures key exists
        - Appends value to config_values
        - Updates config_published pointer
        - Creates a new deployment version (unless _skip_version=True)

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

        # Create a new deployment version after publishing (unless skipped)
        if not _skip_version:
            self._create_deployment_version_for_change(
                project_id=project_id,
                env=env,
                change_summary=f"Updated {key}",
                created_by=created_by,
            )

        return value_id

    def create_or_update_mask(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        name: str | None = None,
        is_ab: bool = False,
        experiment_type: str | None = None,
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
            is_ab: Whether this is an A/B test (deprecated, use experiment_type)
            experiment_type: Type of experiment ('live', 'ab', 'optimizer')
            distribution: Variant weights, e.g. {"A": 50, "B": 50}
            salt: Salt for hashing (auto-generated if not provided)

        Returns:
            The experiment name (generated or provided)
        """
        if salt is None:
            salt = hashlib.sha256(f"{project_id}:{env}:{mask_id}".encode()).hexdigest()[:16]

        if name is None:
            name = generate_experiment_name()

        # Derive experiment_type from is_ab for backwards compatibility
        if experiment_type is None:
            experiment_type = "ab" if is_ab else "live"

        distribution_json = json.dumps(distribution) if distribution else None

        now = self._now()
        with self._transaction() as conn:
            conn.execute("""
                INSERT INTO masks (project_id, env, mask_id, name, is_ab, experiment_type, salt, distribution_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, env, mask_id) DO UPDATE SET
                    name = COALESCE(excluded.name, masks.name),
                    is_ab = excluded.is_ab,
                    experiment_type = COALESCE(masks.experiment_type, excluded.experiment_type),
                    salt = excluded.salt,
                    distribution_json = excluded.distribution_json,
                    updated_at = excluded.updated_at
            """, (project_id, env, mask_id, name, int(is_ab), experiment_type, salt, distribution_json, now, now))

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
        # Ensure the mask exists (auto-create if not)
        self.create_or_update_mask(project_id, env, mask_id, is_ab=False)

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

    def get_mask(
        self,
        project_id: str,
        env: str,
        mask_id: str,
    ) -> dict[str, Any] | None:
        """
        Get a mask/experiment by ID.

        Returns the mask info including experiment_type, or None if not found.
        """
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, mask_id, name, is_ab, experiment_type, salt, distribution_json,
                       created_at, updated_at
                FROM masks
                WHERE project_id = ? AND env = ? AND mask_id = ?
            """, (project_id, env, mask_id)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "mask_id": row["mask_id"],
                "name": row["name"],
                "is_ab": bool(row["is_ab"]),
                "experiment_type": row["experiment_type"],
                "salt": row["salt"],
                "distribution": json.loads(row["distribution_json"]) if row["distribution_json"] else None,
                "created_at": row["created_at"],
                "updated_at": row["updated_at"],
            }

    def find_mask_by_id(self, mask_id: str) -> dict[str, Any] | None:
        """
        Find a mask/experiment by mask_id across all projects.

        This is useful when we have a mask_id (e.g., experiment ID) but don't know
        the project_id. Since mask_ids are typically UUIDs, they are globally unique.

        Returns the mask info including experiment_type and project_id, or None if not found.
        """
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, project_id, env, mask_id, name, is_ab, experiment_type,
                       salt, distribution_json, created_at, updated_at
                FROM masks
                WHERE mask_id = ?
                LIMIT 1
            """, (mask_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "project_id": row["project_id"],
                "env": row["env"],
                "mask_id": row["mask_id"],
                "name": row["name"],
                "is_ab": bool(row["is_ab"]),
                "experiment_type": row["experiment_type"],
                "salt": row["salt"],
                "distribution": json.loads(row["distribution_json"]) if row["distribution_json"] else None,
                "created_at": row["created_at"],
                "updated_at": row["updated_at"],
            }

    # =========================================================================
    # Query helpers for UI/debugging
    # =========================================================================

    def list_keys(self, project_id: str) -> list[dict[str, Any]]:
        """List all registered keys for a project."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT key, type, default_hash, source_json, annotations_json, created_at, updated_at
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
                    "annotations": json.loads(row["annotations_json"]) if row["annotations_json"] else None,
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

    def find_key_by_field_name(self, project_id: str, field_name: str) -> str | None:
        """
        Find the config key by field name.

        Searches registered keys where source_json.field_name matches.
        Returns the full key string (e.g., "ModerationConfig.risk_tolerance") or None.
        """
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT key, source_json FROM config_keys WHERE project_id = ?
            """, (project_id,)).fetchall()

            for row in rows:
                try:
                    if row["source_json"]:
                        source = json.loads(row["source_json"])
                        if source.get("field_name") == field_name:
                            return row["key"]
                except (json.JSONDecodeError, TypeError):
                    continue

            return None

    def list_published(self, project_id: str, env: str) -> list[dict[str, Any]]:
        """List all published values for an environment."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT ck.key, cv.value_json, cp.value_id, cp.updated_at,
                       orig.value_json as original_value_json,
                       ver.version_count,
                       ck.annotations_json
                FROM config_published cp
                JOIN config_keys ck ON ck.id = cp.key_id
                JOIN config_values cv ON cv.id = cp.value_id
                LEFT JOIN (
                    SELECT key_id, value_json,
                           ROW_NUMBER() OVER (PARTITION BY key_id ORDER BY id ASC) as rn
                    FROM config_values
                ) orig ON orig.key_id = ck.id AND orig.rn = 1
                LEFT JOIN (
                    SELECT key_id, COUNT(*) as version_count
                    FROM config_values
                    GROUP BY key_id
                ) ver ON ver.key_id = ck.id
                WHERE cp.project_id = ? AND cp.env = ?
                ORDER BY ck.key
            """, (project_id, env)).fetchall()

            return [
                {
                    "key": row["key"],
                    "value": json.loads(row["value_json"]),
                    "value_id": row["value_id"],
                    "updated_at": row["updated_at"],
                    "original_value": json.loads(row["original_value_json"]) if row["original_value_json"] else json.loads(row["value_json"]),
                    "version": row["version_count"] or 1,
                    "annotations": json.loads(row["annotations_json"]) if row["annotations_json"] else None,
                }
                for row in rows
            ]

    def list_masks(self, project_id: str, env: str) -> list[dict[str, Any]]:
        """List all masks for an environment."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT mask_id, name, is_ab, experiment_type, salt, distribution_json, created_at, updated_at
                FROM masks
                WHERE project_id = ? AND env = ?
                ORDER BY created_at DESC
            """, (project_id, env)).fetchall()

            return [
                {
                    "mask_id": row["mask_id"],
                    "name": row["name"],
                    "is_ab": bool(row["is_ab"]),
                    "experiment_type": row["experiment_type"],
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

    # =========================================================================
    # Prompt Mapping API (for Opik integration)
    # =========================================================================

    def register_prompt_mapping(
        self,
        project_id: str,
        config_key: str,
        prompt_name: str,
        opik_prompt_id: str | None = None,
        commit: str | None = None,
        version_id: str | None = None,
    ) -> None:
        """
        Register or update a mapping between a config key and an Opik prompt.

        Args:
            project_id: Project identifier
            config_key: Config key (e.g., "AgentConfig.researcher_system_prompt")
            prompt_name: Human-readable prompt name
            opik_prompt_id: UUID of the prompt in Opik
            commit: 8-char commit hash of the latest version
            version_id: UUID of the latest version in Opik
        """
        now = self._now()
        with self._transaction() as conn:
            conn.execute("""
                INSERT INTO prompt_mappings (
                    project_id, config_key, prompt_name, opik_prompt_id,
                    latest_commit, latest_opik_version_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, prompt_name) DO UPDATE SET
                    config_key = excluded.config_key,
                    opik_prompt_id = COALESCE(excluded.opik_prompt_id, prompt_mappings.opik_prompt_id),
                    latest_commit = COALESCE(excluded.latest_commit, prompt_mappings.latest_commit),
                    latest_opik_version_id = COALESCE(excluded.latest_opik_version_id, prompt_mappings.latest_opik_version_id),
                    updated_at = excluded.updated_at
            """, (project_id, config_key, prompt_name, opik_prompt_id, commit, version_id, now, now))

    def get_prompt_mapping(self, project_id: str, prompt_name: str) -> dict[str, Any] | None:
        """
        Get the Opik mapping for a prompt.

        Returns dict with: config_key, prompt_name, opik_prompt_id, latest_commit,
        latest_opik_version_id, or None if not found.
        """
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT config_key, prompt_name, opik_prompt_id, latest_commit,
                       latest_opik_version_id, created_at, updated_at
                FROM prompt_mappings
                WHERE project_id = ? AND prompt_name = ?
            """, (project_id, prompt_name)).fetchone()

            if not row:
                return None

            return {
                "config_key": row["config_key"],
                "prompt_name": row["prompt_name"],
                "opik_prompt_id": row["opik_prompt_id"],
                "latest_commit": row["latest_commit"],
                "latest_opik_version_id": row["latest_opik_version_id"],
                "created_at": row["created_at"],
                "updated_at": row["updated_at"],
            }

    def list_prompt_mappings(self, project_id: str) -> list[dict[str, Any]]:
        """List all prompt mappings for a project."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT config_key, prompt_name, opik_prompt_id, latest_commit,
                       latest_opik_version_id, created_at, updated_at
                FROM prompt_mappings
                WHERE project_id = ?
                ORDER BY prompt_name
            """, (project_id,)).fetchall()

            return [
                {
                    "config_key": row["config_key"],
                    "prompt_name": row["prompt_name"],
                    "opik_prompt_id": row["opik_prompt_id"],
                    "latest_commit": row["latest_commit"],
                    "latest_opik_version_id": row["latest_opik_version_id"],
                    "created_at": row["created_at"],
                    "updated_at": row["updated_at"],
                }
                for row in rows
            ]

    def get_experiment_prompt_value(
        self,
        project_id: str,
        env: str,
        mask_id: str,
        prompt_name: str,
        variant: str = "default",
    ) -> dict[str, Any] | None:
        """
        Get the current override value for a prompt in an experiment.

        Returns the value dict or None if no override exists.
        """
        key = self.find_key_by_prompt_name(project_id, env, prompt_name)
        if not key:
            return None

        with self._transaction() as conn:
            key_row = conn.execute(
                "SELECT id FROM config_keys WHERE project_id = ? AND key = ?",
                (project_id, key)
            ).fetchone()

            if not key_row:
                return None

            override = conn.execute("""
                SELECT cv.value_json
                FROM mask_overrides mo
                JOIN config_values cv ON cv.id = mo.value_id
                WHERE mo.project_id = ? AND mo.env = ? AND mo.mask_id = ?
                  AND mo.variant = ? AND mo.key_id = ?
            """, (project_id, env, mask_id, variant, key_row["id"])).fetchone()

            if not override:
                return None

            return json.loads(override["value_json"])

    # =========================================================================
    # Mock Opik Prompt Store (for demo/POC)
    # =========================================================================

    def _generate_commit_hash(self, template: str, metadata: dict[str, Any] | None) -> str:
        """Generate an 8-char commit hash like Opik does."""
        content = template + json.dumps(metadata or {}, sort_keys=True)
        return hashlib.sha256(content.encode()).hexdigest()[:8]

    def mock_create_prompt(
        self,
        name: str,
        template: str,
        metadata: dict[str, Any] | None = None,
        description: str | None = None,
        change_description: str | None = None,
        template_structure: str = "text",
        created_by: str | None = None,
    ) -> dict[str, Any]:
        """
        Create or update a prompt in the mock store (mimics Opik behavior).

        If template/metadata unchanged from latest version, returns existing version.
        Otherwise creates a new version.

        Returns: PromptWithLatestVersion shape for Opik frontend compatibility.
        """
        now = self._now()
        commit = self._generate_commit_hash(template, metadata)

        with self._transaction() as conn:
            # Check if prompt exists
            prompt_row = conn.execute(
                "SELECT id FROM mock_prompts WHERE name = ?",
                (name,)
            ).fetchone()

            if prompt_row:
                prompt_id = prompt_row["id"]
                # Check if latest version has same commit (no change)
                latest = conn.execute("""
                    SELECT id, "commit", template, metadata_json, created_at
                    FROM mock_prompt_versions
                    WHERE prompt_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                """, (prompt_id,)).fetchone()

                if latest and latest["commit"] == commit:
                    # Get version count
                    count_row = conn.execute(
                        "SELECT COUNT(*) as cnt FROM mock_prompt_versions WHERE prompt_id = ?",
                        (prompt_id,)
                    ).fetchone()
                    version_count = count_row["cnt"] if count_row else 0

                    return {
                        "id": prompt_id,
                        "name": name,
                        "description": description or "",
                        "template_structure": template_structure,
                        "created_at": now,
                        "last_updated_at": now,
                        "version_count": version_count,
                        "tags": [],
                        "latest_version": {
                            "id": latest["id"],
                            "prompt_id": prompt_id,
                            "commit": latest["commit"],
                            "template": latest["template"],
                            "metadata": json.loads(latest["metadata_json"]) if latest["metadata_json"] else {},
                            "created_at": latest["created_at"],
                        },
                    }

                # Update description if provided
                if description:
                    conn.execute(
                        "UPDATE mock_prompts SET description = ?, updated_at = ? WHERE id = ?",
                        (description, now, prompt_id)
                    )
            else:
                # Create new prompt
                prompt_id = str(uuid.uuid4())
                conn.execute("""
                    INSERT INTO mock_prompts (id, name, description, template_structure, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (prompt_id, name, description, template_structure, now, now))

            # Create new version
            version_id = str(uuid.uuid4())
            metadata_json = json.dumps(metadata) if metadata else None

            conn.execute("""
                INSERT INTO mock_prompt_versions
                    (id, prompt_id, "commit", template, metadata_json, change_description, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (version_id, prompt_id, commit, template, metadata_json, change_description, now, created_by))

            # Update prompt's updated_at
            conn.execute(
                "UPDATE mock_prompts SET updated_at = ? WHERE id = ?",
                (now, prompt_id)
            )

            # Get version count
            count_row = conn.execute(
                "SELECT COUNT(*) as cnt FROM mock_prompt_versions WHERE prompt_id = ?",
                (prompt_id,)
            ).fetchone()
            version_count = count_row["cnt"] if count_row else 0

            return {
                "id": prompt_id,
                "name": name,
                "description": description or "",
                "template_structure": template_structure,
                "created_at": now,
                "last_updated_at": now,
                "version_count": version_count,
                "tags": [],
                "latest_version": {
                    "id": version_id,
                    "prompt_id": prompt_id,
                    "commit": commit,
                    "template": template,
                    "metadata": metadata or {},
                    "change_description": change_description,
                    "created_at": now,
                },
            }

    def mock_get_prompt(
        self,
        name: str,
        commit: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Get a prompt version from the mock store.

        If commit is None, returns latest version.
        """
        with self._transaction() as conn:
            prompt_row = conn.execute(
                "SELECT id, template_structure FROM mock_prompts WHERE name = ?",
                (name,)
            ).fetchone()

            if not prompt_row:
                return None

            prompt_id = prompt_row["id"]
            template_structure = prompt_row["template_structure"] or "text"

            if commit:
                version = conn.execute("""
                    SELECT id, "commit", template, metadata_json, created_at, created_by
                    FROM mock_prompt_versions
                    WHERE prompt_id = ? AND "commit" = ?
                """, (prompt_id, commit)).fetchone()
            else:
                version = conn.execute("""
                    SELECT id, "commit", template, metadata_json, created_at, created_by
                    FROM mock_prompt_versions
                    WHERE prompt_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                """, (prompt_id,)).fetchone()

            if not version:
                return None

            return {
                "id": version["id"],
                "prompt_id": prompt_id,
                "name": name,
                "commit": version["commit"],
                "template": version["template"],
                "metadata": json.loads(version["metadata_json"]) if version["metadata_json"] else None,
                "template_structure": template_structure,
                "created_at": version["created_at"],
                "created_by": version["created_by"],
            }

    def mock_list_prompts(self) -> list[dict[str, Any]]:
        """List all prompts in the mock store."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, name, description, template_structure, created_at, updated_at
                FROM mock_prompts
                ORDER BY name
            """).fetchall()

            return [
                {
                    "id": r["id"],
                    "name": r["name"],
                    "description": r["description"],
                    "template_structure": r["template_structure"],
                    "created_at": r["created_at"],
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

    def mock_list_prompt_versions(self, name: str) -> list[dict[str, Any]]:
        """List all versions for a prompt by name."""
        with self._transaction() as conn:
            prompt_row = conn.execute(
                "SELECT id FROM mock_prompts WHERE name = ?",
                (name,)
            ).fetchone()

            if not prompt_row:
                return []

            rows = conn.execute("""
                SELECT id, "commit", template, metadata_json, change_description, created_at, created_by
                FROM mock_prompt_versions
                WHERE prompt_id = ?
                ORDER BY created_at DESC
            """, (prompt_row["id"],)).fetchall()

            return [
                {
                    "id": r["id"],
                    "prompt_id": prompt_row["id"],
                    "name": name,
                    "commit": r["commit"],
                    "template": r["template"],
                    "metadata": json.loads(r["metadata_json"]) if r["metadata_json"] else None,
                    "change_description": r["change_description"],
                    "created_at": r["created_at"],
                    "created_by": r["created_by"],
                }
                for r in rows
            ]

    def mock_list_prompts_with_versions(self) -> list[dict[str, Any]]:
        """
        List all prompts with version count and latest_version.
        Matches Opik frontend's Prompt type.
        """
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, name, description, template_structure, created_at, updated_at
                FROM mock_prompts
                ORDER BY name
            """).fetchall()

            results = []
            for r in rows:
                prompt_id = r["id"]

                # Get version count
                count_row = conn.execute(
                    "SELECT COUNT(*) as cnt FROM mock_prompt_versions WHERE prompt_id = ?",
                    (prompt_id,)
                ).fetchone()
                version_count = count_row["cnt"] if count_row else 0

                # Get latest version
                latest = conn.execute("""
                    SELECT id, "commit", template, metadata_json, change_description, created_at, created_by
                    FROM mock_prompt_versions
                    WHERE prompt_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                """, (prompt_id,)).fetchone()

                latest_version = None
                if latest:
                    latest_version = {
                        "id": latest["id"],
                        "prompt_id": prompt_id,
                        "commit": latest["commit"],
                        "template": latest["template"],
                        "metadata": json.loads(latest["metadata_json"]) if latest["metadata_json"] else {},
                        "change_description": latest["change_description"],
                        "created_at": latest["created_at"],
                    }

                results.append({
                    "id": prompt_id,
                    "name": r["name"],
                    "description": r["description"] or "",
                    "template_structure": r["template_structure"] or "text",
                    "created_at": r["created_at"],
                    "last_updated_at": r["updated_at"],
                    "version_count": version_count,
                    "tags": [],
                    "latest_version": latest_version,
                })

            return results

    def mock_get_prompt_by_id(self, prompt_id: str) -> dict[str, Any] | None:
        """
        Get a prompt by ID with latest version.
        Matches Opik frontend's PromptWithLatestVersion type.
        """
        with self._transaction() as conn:
            prompt_row = conn.execute(
                "SELECT id, name, description, template_structure, created_at, updated_at FROM mock_prompts WHERE id = ?",
                (prompt_id,)
            ).fetchone()

            if not prompt_row:
                return None

            # Get version count
            count_row = conn.execute(
                "SELECT COUNT(*) as cnt FROM mock_prompt_versions WHERE prompt_id = ?",
                (prompt_id,)
            ).fetchone()
            version_count = count_row["cnt"] if count_row else 0

            # Get latest version
            latest = conn.execute("""
                SELECT id, "commit", template, metadata_json, change_description, created_at, created_by
                FROM mock_prompt_versions
                WHERE prompt_id = ?
                ORDER BY created_at DESC
                LIMIT 1
            """, (prompt_id,)).fetchone()

            latest_version = None
            if latest:
                latest_version = {
                    "id": latest["id"],
                    "prompt_id": prompt_id,
                    "commit": latest["commit"],
                    "template": latest["template"],
                    "metadata": json.loads(latest["metadata_json"]) if latest["metadata_json"] else {},
                    "change_description": latest["change_description"],
                    "created_at": latest["created_at"],
                }

            return {
                "id": prompt_id,
                "name": prompt_row["name"],
                "description": prompt_row["description"] or "",
                "template_structure": prompt_row["template_structure"] or "text",
                "created_at": prompt_row["created_at"],
                "last_updated_at": prompt_row["updated_at"],
                "version_count": version_count,
                "tags": [],
                "latest_version": latest_version,
            }

    def mock_get_version_by_id(self, version_id: str) -> dict[str, Any] | None:
        """
        Get a single prompt version by version ID.
        Matches Opik frontend's PromptVersion type.
        """
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT v.id, v.prompt_id, v."commit", v.template, v.metadata_json,
                       v.change_description, v.created_at, v.created_by, p.name as prompt_name
                FROM mock_prompt_versions v
                JOIN mock_prompts p ON p.id = v.prompt_id
                WHERE v.id = ?
            """, (version_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "prompt_id": row["prompt_id"],
                "commit": row["commit"],
                "template": row["template"],
                "metadata": json.loads(row["metadata_json"]) if row["metadata_json"] else {},
                "change_description": row["change_description"],
                "created_at": row["created_at"],
            }

    def mock_list_prompt_versions_by_id(self, prompt_id: str) -> list[dict[str, Any]]:
        """
        List all versions for a prompt by ID.
        Matches Opik frontend's PromptVersion type.
        """
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, "commit", template, metadata_json, change_description, created_at, created_by
                FROM mock_prompt_versions
                WHERE prompt_id = ?
                ORDER BY created_at DESC
            """, (prompt_id,)).fetchall()

            return [
                {
                    "id": r["id"],
                    "prompt_id": prompt_id,
                    "commit": r["commit"],
                    "template": r["template"],
                    "metadata": json.loads(r["metadata_json"]) if r["metadata_json"] else {},
                    "change_description": r["change_description"],
                    "created_at": r["created_at"],
                }
                for r in rows
            ]

    def mock_create_prompt_version(
        self,
        prompt_id: str,
        template: str,
        metadata: dict[str, Any] | None = None,
        change_description: str | None = None,
        created_by: str | None = None,
    ) -> dict[str, Any] | None:
        """Create a new version for an existing prompt."""
        now = self._now()
        commit = self._generate_commit_hash(template, metadata)

        with self._transaction() as conn:
            # Check prompt exists
            prompt_row = conn.execute(
                "SELECT id, name FROM mock_prompts WHERE id = ?",
                (prompt_id,)
            ).fetchone()

            if not prompt_row:
                return None

            # Check if latest version has same commit (no change)
            latest = conn.execute("""
                SELECT "commit" FROM mock_prompt_versions
                WHERE prompt_id = ?
                ORDER BY created_at DESC
                LIMIT 1
            """, (prompt_id,)).fetchone()

            if latest and latest["commit"] == commit:
                # No change, return existing
                existing = conn.execute("""
                    SELECT id, "commit", template, metadata_json, change_description, created_at
                    FROM mock_prompt_versions
                    WHERE prompt_id = ? AND "commit" = ?
                """, (prompt_id, commit)).fetchone()
                return {
                    "id": existing["id"],
                    "prompt_id": prompt_id,
                    "commit": existing["commit"],
                    "template": existing["template"],
                    "metadata": json.loads(existing["metadata_json"]) if existing["metadata_json"] else {},
                    "change_description": existing["change_description"],
                    "created_at": existing["created_at"],
                }

            # Create new version
            version_id = str(uuid.uuid4())
            metadata_json = json.dumps(metadata) if metadata else None

            conn.execute("""
                INSERT INTO mock_prompt_versions
                    (id, prompt_id, "commit", template, metadata_json, change_description, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (version_id, prompt_id, commit, template, metadata_json, change_description, now, created_by))

            # Update prompt's updated_at
            conn.execute(
                "UPDATE mock_prompts SET updated_at = ? WHERE id = ?",
                (now, prompt_id)
            )

            return {
                "id": version_id,
                "prompt_id": prompt_id,
                "commit": commit,
                "template": template,
                "metadata": metadata or {},
                "change_description": change_description,
                "created_at": now,
            }

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

            # prompt_mappings
            rows = conn.execute("SELECT * FROM prompt_mappings ORDER BY id").fetchall()
            result["prompt_mappings"] = [
                {
                    "id": r["id"],
                    "project_id": r["project_id"],
                    "config_key": r["config_key"],
                    "prompt_name": r["prompt_name"],
                    "opik_prompt_id": r["opik_prompt_id"],
                    "latest_commit": r["latest_commit"],
                    "latest_opik_version_id": r["latest_opik_version_id"],
                    "created_at": r["created_at"],
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

            # mock_prompts
            rows = conn.execute("SELECT * FROM mock_prompts ORDER BY name").fetchall()
            result["mock_prompts"] = [
                {
                    "id": r["id"],
                    "name": r["name"],
                    "description": r["description"],
                    "template_structure": r["template_structure"],
                    "created_at": r["created_at"],
                    "updated_at": r["updated_at"],
                }
                for r in rows
            ]

            # mock_prompt_versions
            rows = conn.execute("""
                SELECT v.*, p.name as prompt_name
                FROM mock_prompt_versions v
                JOIN mock_prompts p ON p.id = v.prompt_id
                ORDER BY p.name, v.created_at DESC
            """).fetchall()
            result["mock_prompt_versions"] = [
                {
                    "id": r["id"],
                    "prompt_id": r["prompt_id"],
                    "prompt_name": r["prompt_name"],
                    "commit": r["commit"],
                    "template": r["template"],
                    "metadata": json.loads(r["metadata_json"]) if r["metadata_json"] else None,
                    "change_description": r["change_description"],
                    "created_at": r["created_at"],
                    "created_by": r["created_by"],
                }
                for r in rows
            ]

            return result

    # =========================================================================
    # Evaluation Suites API
    # =========================================================================

    def create_eval_suite(
        self,
        name: str,
        project_id: str,
        created_by: str | None = None,
    ) -> dict[str, Any]:
        """
        Create a new evaluation suite.

        Returns the created suite with id, name, and item_count.
        """
        now = self._now()
        suite_id = str(uuid.uuid4())

        with self._transaction() as conn:
            conn.execute("""
                INSERT INTO eval_suites (id, name, project_id, created_at, created_by)
                VALUES (?, ?, ?, ?, ?)
            """, (suite_id, name, project_id, now, created_by))

            return {
                "id": suite_id,
                "name": name,
                "project_id": project_id,
                "item_count": 0,
                "created_at": now,
                "created_by": created_by,
            }

    def get_eval_suite(self, suite_id: str) -> dict[str, Any] | None:
        """Get an evaluation suite by ID."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, name, project_id, created_at, created_by
                FROM eval_suites
                WHERE id = ?
            """, (suite_id,)).fetchone()

            if not row:
                return None

            count_row = conn.execute(
                "SELECT COUNT(*) as cnt FROM eval_suite_items WHERE suite_id = ?",
                (suite_id,)
            ).fetchone()
            item_count = count_row["cnt"] if count_row else 0

            return {
                "id": row["id"],
                "name": row["name"],
                "project_id": row["project_id"],
                "item_count": item_count,
                "created_at": row["created_at"],
                "created_by": row["created_by"],
            }

    def get_eval_suite_by_name(self, project_id: str, name: str) -> dict[str, Any] | None:
        """Get an evaluation suite by project and name."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, name, project_id, created_at, created_by
                FROM eval_suites
                WHERE project_id = ? AND name = ?
            """, (project_id, name)).fetchone()

            if not row:
                return None

            count_row = conn.execute(
                "SELECT COUNT(*) as cnt FROM eval_suite_items WHERE suite_id = ?",
                (row["id"],)
            ).fetchone()
            item_count = count_row["cnt"] if count_row else 0

            return {
                "id": row["id"],
                "name": row["name"],
                "project_id": row["project_id"],
                "item_count": item_count,
                "created_at": row["created_at"],
                "created_by": row["created_by"],
            }

    def list_eval_suites(self, project_id: str) -> list[dict[str, Any]]:
        """List all evaluation suites for a project."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, name, project_id, created_at, created_by
                FROM eval_suites
                WHERE project_id = ?
                ORDER BY name
            """, (project_id,)).fetchall()

            results = []
            for row in rows:
                count_row = conn.execute(
                    "SELECT COUNT(*) as cnt FROM eval_suite_items WHERE suite_id = ?",
                    (row["id"],)
                ).fetchone()
                item_count = count_row["cnt"] if count_row else 0

                results.append({
                    "id": row["id"],
                    "name": row["name"],
                    "project_id": row["project_id"],
                    "item_count": item_count,
                    "created_at": row["created_at"],
                    "created_by": row["created_by"],
                })

            return results

    def delete_eval_suite(self, suite_id: str) -> bool:
        """Delete an evaluation suite and all its items."""
        with self._transaction() as conn:
            row = conn.execute(
                "SELECT id FROM eval_suites WHERE id = ?",
                (suite_id,)
            ).fetchone()

            if not row:
                return False

            conn.execute("DELETE FROM eval_suites WHERE id = ?", (suite_id,))
            return True

    def add_eval_suite_item(
        self,
        suite_id: str,
        input_data: dict[str, Any],
        assertions: list[str],
        trace_id: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Add an item to an evaluation suite.

        Returns the created item or None if suite doesn't exist.
        """
        now = self._now()
        item_id = str(uuid.uuid4())

        with self._transaction() as conn:
            suite_row = conn.execute(
                "SELECT id FROM eval_suites WHERE id = ?",
                (suite_id,)
            ).fetchone()

            if not suite_row:
                return None

            conn.execute("""
                INSERT INTO eval_suite_items (id, suite_id, input_data_json, assertions_json, trace_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (item_id, suite_id, json.dumps(input_data), json.dumps(assertions), trace_id, now))

            return {
                "id": item_id,
                "suite_id": suite_id,
                "input_data": input_data,
                "assertions": assertions,
                "trace_id": trace_id,
                "created_at": now,
            }

    def list_eval_suite_items(self, suite_id: str) -> list[dict[str, Any]]:
        """List all items in an evaluation suite."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, suite_id, input_data_json, assertions_json, trace_id, created_at
                FROM eval_suite_items
                WHERE suite_id = ?
                ORDER BY created_at
            """, (suite_id,)).fetchall()

            return [
                {
                    "id": row["id"],
                    "suite_id": row["suite_id"],
                    "input_data": json.loads(row["input_data_json"]),
                    "assertions": json.loads(row["assertions_json"]),
                    "trace_id": row["trace_id"],
                    "created_at": row["created_at"],
                }
                for row in rows
            ]

    def get_eval_suite_item(self, item_id: str) -> dict[str, Any] | None:
        """Get a single evaluation suite item by ID."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, suite_id, input_data_json, assertions_json, trace_id, created_at
                FROM eval_suite_items
                WHERE id = ?
            """, (item_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "suite_id": row["suite_id"],
                "input_data": json.loads(row["input_data_json"]),
                "assertions": json.loads(row["assertions_json"]),
                "trace_id": row["trace_id"],
                "created_at": row["created_at"],
            }

    def delete_eval_suite_item(self, suite_id: str, item_id: str) -> bool:
        """Delete an item from an evaluation suite."""
        with self._transaction() as conn:
            row = conn.execute(
                "SELECT id FROM eval_suite_items WHERE id = ? AND suite_id = ?",
                (item_id, suite_id)
            ).fetchone()

            if not row:
                return False

            conn.execute("DELETE FROM eval_suite_items WHERE id = ?", (item_id,))
            return True

    # =========================================================================
    # Evaluation Runs API
    # =========================================================================

    def create_eval_run(
        self,
        suite_id: str,
        experiment_id: str | None = None,
        created_by: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Create a new evaluation run for a suite.

        Returns the created run or None if suite doesn't exist.
        """
        now = self._now()
        run_id = str(uuid.uuid4())

        with self._transaction() as conn:
            suite_row = conn.execute(
                "SELECT id FROM eval_suites WHERE id = ?",
                (suite_id,)
            ).fetchone()

            if not suite_row:
                return None

            conn.execute("""
                INSERT INTO eval_runs (id, suite_id, status, experiment_id, created_at, created_by)
                VALUES (?, ?, 'pending', ?, ?, ?)
            """, (run_id, suite_id, experiment_id, now, created_by))

            return {
                "id": run_id,
                "suite_id": suite_id,
                "status": "pending",
                "started_at": None,
                "completed_at": None,
                "experiment_id": experiment_id,
                "total_items": 0,
                "passed_items": 0,
                "pass_rate": 0.0,
                "created_at": now,
                "created_by": created_by,
            }

    def update_eval_run_status(
        self,
        run_id: str,
        status: str,
        total_items: int | None = None,
        passed_items: int | None = None,
    ) -> bool:
        """
        Update the status (and optionally stats) of an evaluation run.

        Returns True if updated, False if run not found.
        """
        now = self._now()

        with self._transaction() as conn:
            row = conn.execute(
                "SELECT id, status FROM eval_runs WHERE id = ?",
                (run_id,)
            ).fetchone()

            if not row:
                return False

            updates = ["status = ?"]
            params: list[Any] = [status]

            if status == "running" and row["status"] == "pending":
                updates.append("started_at = ?")
                params.append(now)

            if status in ("completed", "failed"):
                updates.append("completed_at = ?")
                params.append(now)

            if total_items is not None:
                updates.append("total_items = ?")
                params.append(total_items)

            if passed_items is not None:
                updates.append("passed_items = ?")
                params.append(passed_items)

            if total_items is not None and passed_items is not None and total_items > 0:
                pass_rate = passed_items / total_items
                updates.append("pass_rate = ?")
                params.append(pass_rate)

            params.append(run_id)
            conn.execute(f"UPDATE eval_runs SET {', '.join(updates)} WHERE id = ?", params)
            return True

    def get_eval_run(self, run_id: str) -> dict[str, Any] | None:
        """Get an evaluation run by ID."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, suite_id, status, started_at, completed_at, experiment_id,
                       total_items, passed_items, pass_rate, created_at, created_by
                FROM eval_runs
                WHERE id = ?
            """, (run_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "suite_id": row["suite_id"],
                "status": row["status"],
                "started_at": row["started_at"],
                "completed_at": row["completed_at"],
                "experiment_id": row["experiment_id"],
                "total_items": row["total_items"],
                "passed_items": row["passed_items"],
                "pass_rate": row["pass_rate"],
                "created_at": row["created_at"],
                "created_by": row["created_by"],
            }

    def list_eval_runs(self, suite_id: str, limit: int = 20) -> list[dict[str, Any]]:
        """List evaluation runs for a suite, newest first."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, suite_id, status, started_at, completed_at, experiment_id,
                       total_items, passed_items, pass_rate, created_at, created_by
                FROM eval_runs
                WHERE suite_id = ?
                ORDER BY created_at DESC
                LIMIT ?
            """, (suite_id, limit)).fetchall()

            return [
                {
                    "id": row["id"],
                    "suite_id": row["suite_id"],
                    "status": row["status"],
                    "started_at": row["started_at"],
                    "completed_at": row["completed_at"],
                    "experiment_id": row["experiment_id"],
                    "total_items": row["total_items"],
                    "passed_items": row["passed_items"],
                    "pass_rate": row["pass_rate"],
                    "created_at": row["created_at"],
                    "created_by": row["created_by"],
                }
                for row in rows
            ]

    def delete_eval_run(self, run_id: str) -> bool:
        """Delete an evaluation run and all its results."""
        with self._transaction() as conn:
            row = conn.execute(
                "SELECT id FROM eval_runs WHERE id = ?",
                (run_id,)
            ).fetchone()

            if not row:
                return False

            conn.execute("DELETE FROM eval_runs WHERE id = ?", (run_id,))
            return True

    def add_eval_run_result(
        self,
        run_id: str,
        item_id: str,
        passed: bool,
        assertion_results: list[dict[str, Any]],
        trace_id: str | None = None,
        duration_ms: int | None = None,
        error_message: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Add a result for an item in an evaluation run.

        Returns the created result or None if run/item doesn't exist.
        """
        now = self._now()
        result_id = str(uuid.uuid4())

        with self._transaction() as conn:
            run_row = conn.execute(
                "SELECT id FROM eval_runs WHERE id = ?",
                (run_id,)
            ).fetchone()

            if not run_row:
                return None

            item_row = conn.execute(
                "SELECT id FROM eval_suite_items WHERE id = ?",
                (item_id,)
            ).fetchone()

            if not item_row:
                return None

            conn.execute("""
                INSERT INTO eval_run_results (id, run_id, item_id, passed, assertion_results_json, trace_id, duration_ms, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (result_id, run_id, item_id, int(passed), json.dumps(assertion_results), trace_id, duration_ms, error_message, now))

            return {
                "id": result_id,
                "run_id": run_id,
                "item_id": item_id,
                "passed": passed,
                "assertion_results": assertion_results,
                "trace_id": trace_id,
                "duration_ms": duration_ms,
                "error_message": error_message,
                "created_at": now,
            }

    def list_eval_run_results(self, run_id: str) -> list[dict[str, Any]]:
        """List all results for an evaluation run with item details."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT r.id, r.run_id, r.item_id, r.passed, r.assertion_results_json,
                       r.trace_id, r.duration_ms, r.error_message, r.created_at,
                       i.input_data_json
                FROM eval_run_results r
                JOIN eval_suite_items i ON i.id = r.item_id
                WHERE r.run_id = ?
                ORDER BY r.created_at
            """, (run_id,)).fetchall()

            return [
                {
                    "id": row["id"],
                    "run_id": row["run_id"],
                    "item_id": row["item_id"],
                    "passed": bool(row["passed"]),
                    "assertion_results": json.loads(row["assertion_results_json"]),
                    "trace_id": row["trace_id"],
                    "duration_ms": row["duration_ms"],
                    "error_message": row["error_message"],
                    "created_at": row["created_at"],
                    "input_data": json.loads(row["input_data_json"]),
                }
                for row in rows
            ]

    # =========================================================================
    # Blueprints API (Heroku-style deployment versioning)
    # =========================================================================

    def create_blueprint(
        self,
        project_id: str,
        name: str,
    ) -> dict[str, Any]:
        """
        Create a new blueprint for a project.

        Returns the created blueprint.
        """
        now = self._now()
        blueprint_id = str(uuid.uuid4())

        with self._transaction() as conn:
            conn.execute("""
                INSERT INTO blueprints (id, project_id, name, created_at)
                VALUES (?, ?, ?, ?)
            """, (blueprint_id, project_id, name, now))

            return {
                "id": blueprint_id,
                "project_id": project_id,
                "name": name,
                "created_at": now,
            }

    def get_blueprint(self, blueprint_id: str) -> dict[str, Any] | None:
        """Get a blueprint by ID."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, project_id, name, created_at
                FROM blueprints
                WHERE id = ?
            """, (blueprint_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "project_id": row["project_id"],
                "name": row["name"],
                "created_at": row["created_at"],
            }

    def get_blueprint_for_project(self, project_id: str) -> dict[str, Any] | None:
        """Get the blueprint for a project."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, project_id, name, created_at
                FROM blueprints
                WHERE project_id = ?
            """, (project_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "project_id": row["project_id"],
                "name": row["name"],
                "created_at": row["created_at"],
            }

    def get_or_create_blueprint_for_project(
        self,
        project_id: str,
        name: str | None = None,
    ) -> dict[str, Any]:
        """Get existing blueprint or create a new one for the project."""
        existing = self.get_blueprint_for_project(project_id)
        if existing:
            return existing

        return self.create_blueprint(
            project_id=project_id,
            name=name or f"Blueprint for {project_id}",
        )

    def _ensure_blueprint_with_initial_version(
        self,
        project_id: str,
        env: str = "prod",
    ) -> dict[str, Any] | None:
        """
        Ensure a blueprint exists with at least one version.
        Called automatically when prompts are first registered.

        Returns the blueprint if created/exists, None if no prompts to snapshot.
        """
        # Check if blueprint already has versions
        existing = self.get_blueprint_for_project(project_id)
        if existing:
            versions = self.list_deployment_versions(existing["id"], limit=1)
            if versions:
                return existing

        # Build snapshot from current published values
        published = self.list_published(project_id, env)
        if not published:
            return None

        snapshot: dict[str, Any] = {"prompts": {}, "config": {}}
        has_prompts = False

        for item in published:
            key = item["key"]
            value = item["value"]
            if isinstance(value, dict) and "prompt_name" in value:
                snapshot["prompts"][key] = value
                has_prompts = True
            else:
                snapshot["config"][key] = value

        if not has_prompts:
            return None

        # Create blueprint and initial version
        blueprint = self.get_or_create_blueprint_for_project(project_id)

        # Check again if versions exist (race condition protection)
        versions = self.list_deployment_versions(blueprint["id"], limit=1)
        if versions:
            return blueprint

        self.create_deployment_version(
            blueprint_id=blueprint["id"],
            snapshot=snapshot,
            change_summary="Initial prompts",
            change_type="manual",
            created_by="system",
        )

        # Set both DEV and PROD to v1
        self.set_environment_pointer(blueprint["id"], "prod", 1)

        return blueprint

    def _create_deployment_version_for_change(
        self,
        project_id: str,
        env: str = "prod",
        change_summary: str | None = None,
        change_type: str = "manual",
        created_by: str | None = None,
    ) -> dict[str, Any] | None:
        """
        Create a new deployment version after a config change.
        Builds a snapshot from current published values and creates a version.

        Returns the created version, or None if no blueprint exists.
        """
        blueprint = self.get_blueprint_for_project(project_id)
        if not blueprint:
            blueprint = self.get_or_create_blueprint_for_project(project_id)

        published = self.list_published(project_id, env)
        if not published:
            return None

        snapshot: dict[str, Any] = {"prompts": {}, "config": {}}
        for item in published:
            key = item["key"]
            value = item["value"]
            if isinstance(value, dict) and "prompt_name" in value:
                snapshot["prompts"][key] = value
            else:
                snapshot["config"][key] = value

        return self.create_deployment_version(
            blueprint_id=blueprint["id"],
            snapshot=snapshot,
            change_summary=change_summary,
            change_type=change_type,
            created_by=created_by,
        )

    def create_deployment_version(
        self,
        blueprint_id: str,
        snapshot: dict[str, Any],
        change_summary: str | None = None,
        change_type: str = "manual",
        source_experiment_id: str | None = None,
        created_by: str | None = None,
    ) -> dict[str, Any]:
        """
        Create a new deployment version (append-only).

        Automatically increments version_number and moves DEV pointer.

        Args:
            blueprint_id: The blueprint to add version to
            snapshot: Full config state snapshot
            change_summary: Human-readable summary of changes
            change_type: "optimizer" | "manual" | "rollback"
            source_experiment_id: Link to optimizer experiment if applicable
            created_by: User who created the version

        Returns the created version with version_number.
        """
        now = self._now()
        version_id = str(uuid.uuid4())

        with self._transaction() as conn:
            # Get next version number
            row = conn.execute("""
                SELECT COALESCE(MAX(version_number), 0) + 1 as next_version
                FROM deployment_versions
                WHERE blueprint_id = ?
            """, (blueprint_id,)).fetchone()
            version_number = row["next_version"]

            # Insert the version
            conn.execute("""
                INSERT INTO deployment_versions (
                    id, blueprint_id, version_number, snapshot_json,
                    change_summary, change_type, source_experiment_id,
                    created_at, created_by
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                version_id, blueprint_id, version_number,
                json.dumps(snapshot), change_summary, change_type,
                source_experiment_id, now, created_by
            ))

            # Auto-move LATEST pointer to new version
            conn.execute("""
                INSERT INTO environment_pointers (blueprint_id, env, version_number, updated_at)
                VALUES (?, 'latest', ?, ?)
                ON CONFLICT(blueprint_id, env) DO UPDATE SET
                    version_number = excluded.version_number,
                    updated_at = excluded.updated_at
            """, (blueprint_id, version_number, now))

            # Initialize PROD pointer if it doesn't exist (set to v1)
            conn.execute("""
                INSERT OR IGNORE INTO environment_pointers (blueprint_id, env, version_number, updated_at)
                VALUES (?, 'prod', 1, ?)
            """, (blueprint_id, now))

            return {
                "id": version_id,
                "blueprint_id": blueprint_id,
                "version_number": version_number,
                "snapshot": snapshot,
                "change_summary": change_summary,
                "change_type": change_type,
                "source_experiment_id": source_experiment_id,
                "created_at": now,
                "created_by": created_by,
            }

    def list_deployment_versions(
        self,
        blueprint_id: str,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """List deployment versions for a blueprint (newest first)."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT id, blueprint_id, version_number, snapshot_json,
                       change_summary, change_type, source_experiment_id,
                       created_at, created_by
                FROM deployment_versions
                WHERE blueprint_id = ?
                ORDER BY version_number DESC
                LIMIT ?
            """, (blueprint_id, limit)).fetchall()

            return [
                {
                    "id": row["id"],
                    "blueprint_id": row["blueprint_id"],
                    "version_number": row["version_number"],
                    "snapshot": json.loads(row["snapshot_json"]),
                    "change_summary": row["change_summary"],
                    "change_type": row["change_type"],
                    "source_experiment_id": row["source_experiment_id"],
                    "created_at": row["created_at"],
                    "created_by": row["created_by"],
                }
                for row in rows
            ]

    def get_deployment_version(self, version_id: str) -> dict[str, Any] | None:
        """Get a specific deployment version by ID."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, blueprint_id, version_number, snapshot_json,
                       change_summary, change_type, source_experiment_id,
                       created_at, created_by
                FROM deployment_versions
                WHERE id = ?
            """, (version_id,)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "blueprint_id": row["blueprint_id"],
                "version_number": row["version_number"],
                "snapshot": json.loads(row["snapshot_json"]),
                "change_summary": row["change_summary"],
                "change_type": row["change_type"],
                "source_experiment_id": row["source_experiment_id"],
                "created_at": row["created_at"],
                "created_by": row["created_by"],
            }

    def get_deployment_version_by_number(
        self,
        blueprint_id: str,
        version_number: int,
    ) -> dict[str, Any] | None:
        """Get a deployment version by blueprint and version number."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT id, blueprint_id, version_number, snapshot_json,
                       change_summary, change_type, source_experiment_id,
                       created_at, created_by
                FROM deployment_versions
                WHERE blueprint_id = ? AND version_number = ?
            """, (blueprint_id, version_number)).fetchone()

            if not row:
                return None

            return {
                "id": row["id"],
                "blueprint_id": row["blueprint_id"],
                "version_number": row["version_number"],
                "snapshot": json.loads(row["snapshot_json"]),
                "change_summary": row["change_summary"],
                "change_type": row["change_type"],
                "source_experiment_id": row["source_experiment_id"],
                "created_at": row["created_at"],
                "created_by": row["created_by"],
            }

    def get_environment_pointer(
        self,
        blueprint_id: str,
        env: str,
    ) -> dict[str, Any] | None:
        """Get the current pointer for an environment."""
        with self._transaction() as conn:
            row = conn.execute("""
                SELECT blueprint_id, env, version_number, updated_at
                FROM environment_pointers
                WHERE blueprint_id = ? AND env = ?
            """, (blueprint_id, env)).fetchone()

            if not row:
                return None

            return {
                "blueprint_id": row["blueprint_id"],
                "env": row["env"],
                "version_number": row["version_number"],
                "updated_at": row["updated_at"],
            }

    def apply_snapshot_to_published(
        self,
        project_id: str,
        env: str,
        snapshot: dict[str, Any],
    ) -> None:
        """
        Apply a deployment version's snapshot to config_published.

        This syncs the versioned snapshot to the live resolution table,
        making the snapshot's values available for resolve() calls.

        Does NOT create new deployment versions (those already exist).
        """
        prompts = snapshot.get("prompts", {})
        config = snapshot.get("config", {})

        for key, value in {**prompts, **config}.items():
            self.publish_value(
                project_id=project_id,
                env=env,
                key=key,
                value=value,
                created_by="promote",
                _skip_version=True,
            )

    def set_environment_pointer(
        self,
        blueprint_id: str,
        env: str,
        version_number: int,
        sync_to_published: bool = True,
    ) -> dict[str, Any]:
        """
        Set the pointer for an environment to a specific version.

        When sync_to_published is True (default) and env is not "latest",
        also syncs the version's snapshot to config_published so that
        resolve() returns the correct values.

        Returns the updated pointer.
        """
        now = self._now()

        with self._transaction() as conn:
            # Verify version exists and get snapshot
            version_row = conn.execute("""
                SELECT id, snapshot_json FROM deployment_versions
                WHERE blueprint_id = ? AND version_number = ?
            """, (blueprint_id, version_number)).fetchone()

            if not version_row:
                raise ValueError(f"Version {version_number} not found for blueprint {blueprint_id}")

            conn.execute("""
                INSERT INTO environment_pointers (blueprint_id, env, version_number, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(blueprint_id, env) DO UPDATE SET
                    version_number = excluded.version_number,
                    updated_at = excluded.updated_at
            """, (blueprint_id, env, version_number, now))

            # Get project_id for this blueprint
            blueprint_row = conn.execute(
                "SELECT project_id FROM blueprints WHERE id = ?",
                (blueprint_id,)
            ).fetchone()

        # Sync to config_published for real environments (not "latest")
        if sync_to_published and env != "latest" and blueprint_row:
            snapshot = json.loads(version_row["snapshot_json"])
            self.apply_snapshot_to_published(
                project_id=blueprint_row["project_id"],
                env=env,
                snapshot=snapshot,
            )

        return {
            "blueprint_id": blueprint_id,
            "env": env,
            "version_number": version_number,
            "updated_at": now,
        }

    def get_blueprint_history(
        self,
        blueprint_id: str,
        limit: int = 50,
    ) -> dict[str, Any]:
        """
        Get deployment history with all environment pointers.

        Returns:
        {
            "versions": [...],
            "latest_version": int,
            "pointers": {"latest": 5, "prod": 3, "stage": 4, ...}
        }
        """
        versions = self.list_deployment_versions(blueprint_id, limit)
        pointers = self.list_environment_pointers(blueprint_id)

        # Build pointers dict
        pointers_dict = {p["env"]: p["version_number"] for p in pointers}

        return {
            "versions": versions,
            "latest_version": pointers_dict.get("latest", 0),
            "pointers": pointers_dict,
        }

    def list_environment_pointers(self, blueprint_id: str) -> list[dict[str, Any]]:
        """List all environment pointers for a blueprint."""
        with self._transaction() as conn:
            rows = conn.execute("""
                SELECT blueprint_id, env, version_number, updated_at
                FROM environment_pointers
                WHERE blueprint_id = ?
                ORDER BY env
            """, (blueprint_id,)).fetchall()

            return [
                {
                    "blueprint_id": row["blueprint_id"],
                    "env": row["env"],
                    "version_number": row["version_number"],
                    "updated_at": row["updated_at"],
                }
                for row in rows
            ]

    def migrate_project_to_blueprint(
        self,
        project_id: str,
        env: str = "prod",
    ) -> dict[str, Any]:
        """
        One-time migration: create a blueprint from existing prompts.

        Reads current published prompts and creates initial deployment version.

        Returns the created blueprint with version info.
        """
        # Get or create blueprint
        blueprint = self.get_or_create_blueprint_for_project(project_id)

        # Check if already has versions
        existing_versions = self.list_deployment_versions(blueprint["id"], limit=1)
        if existing_versions:
            return {
                **blueprint,
                "version_number": existing_versions[0]["version_number"],
                "already_migrated": True,
            }

        # Build snapshot from current published values
        published = self.list_published(project_id, env)
        snapshot = {
            "prompts": {},
            "config": {},
        }

        for item in published:
            key = item["key"]
            value = item["value"]
            if isinstance(value, dict) and "prompt_name" in value:
                snapshot["prompts"][key] = value
            else:
                snapshot["config"][key] = value

        # Create initial version (this auto-sets LATEST to v1)
        version = self.create_deployment_version(
            blueprint_id=blueprint["id"],
            snapshot=snapshot,
            change_summary="Initial migration from existing prompts",
            change_type="manual",
            created_by="system",
        )

        # Set PROD to v1 as well for initial setup
        self.set_environment_pointer(blueprint["id"], "prod", 1)

        return {
            **blueprint,
            "version_number": version["version_number"],
            "already_migrated": False,
        }
