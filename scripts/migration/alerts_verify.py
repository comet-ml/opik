#!/usr/bin/env python3
"""
Post-migration verification.

Loads the raw snapshot from snapshot.py, applies the same logic as
AlertProjectMigrationService.executeAlertMigration() to derive the exact
expected project assignments, then checks those against the current DB state.

Migration logic mirrored from Java (AlertProjectMigrationService):

  1. Collect all project UUIDs from scope:project trigger configs  →  raw_project_ids
  2. Keep only those that exist in the projects table, ORDER BY id ASC  →  valid_project_ids
  3. If valid_project_ids is empty:
       original alert  →  Default Project
  4. Else:
       original alert      →  valid_project_ids[0]   (lexicographically first)
       for each remaining valid project (valid_project_ids[1:]):
           new alert created  →  that project
       if any trigger has no valid project (workspace-wide or all-deleted-project):
           new alert created  →  Default Project

Run AFTER the AlertProjectMigrationJob has finished.

Usage:
    DB_HOST=<your-host> DB_PORT=3306 DB_NAME=<your-db> DB_USER=<your-user> DB_PASSWORD=<your-password> \
        python verify.py alert_migration_snapshot_<timestamp>.json

Exit codes:  0 = all checks passed,  1 = at least one failure
"""

import argparse
import json
import os
import sys

try:
    import pymysql
    import pymysql.cursors
except ImportError:
    print("ERROR: pymysql not installed. Run: pip install pymysql")
    sys.exit(1)

DEFAULT_PROJECT_NAME = "Default Project"


# ---------------------------------------------------------------------------
# DB connection
# ---------------------------------------------------------------------------
def connect():
    missing = [v for v in ("DB_HOST", "DB_NAME", "DB_USER", "DB_PASSWORD") if not os.environ.get(v)]
    if missing:
        print(f"ERROR: Missing required environment variables: {', '.join(missing)}")
        sys.exit(1)
    return pymysql.connect(
        host=os.environ["DB_HOST"],
        port=int(os.environ.get("DB_PORT", "3306")),
        database=os.environ["DB_NAME"],
        user=os.environ["DB_USER"],
        password=os.environ["DB_PASSWORD"],
        cursorclass=pymysql.cursors.DictCursor,
        charset="utf8mb4",
    )


# ---------------------------------------------------------------------------
# DB queries
# ---------------------------------------------------------------------------

def fetch_alert_project_id(cursor, alert_id):
    cursor.execute("SELECT project_id FROM alerts WHERE id = %s", (alert_id,))
    row = cursor.fetchone()
    return row["project_id"] if row else None


def fetch_valid_project_ids(cursor, workspace_id, candidate_ids):
    """
    Mirror of projectService.findByIds — returns UUIDs that exist in the
    projects table for this workspace, sorted lexicographically (ORDER BY id).
    """
    if not candidate_ids:
        return []
    placeholders = ", ".join(["%s"] * len(candidate_ids))
    cursor.execute(
        f"SELECT id FROM projects WHERE workspace_id = %s AND id IN ({placeholders}) ORDER BY id",
        [workspace_id, *candidate_ids],
    )
    return [row["id"] for row in cursor.fetchall()]


def fetch_default_project_id(cursor, workspace_id):
    """Mirror of projectService.getOrCreate — finds Default Project by name."""
    cursor.execute(
        "SELECT id FROM projects WHERE workspace_id = %s AND name = %s ORDER BY id ASC LIMIT 1",
        (workspace_id, DEFAULT_PROJECT_NAME),
    )
    row = cursor.fetchone()
    return row["id"] if row else None


def fetch_new_alert_with_project(cursor, workspace_id, name, project_id, original_id, captured_at):
    """
    Find a NEW alert (not the original) created after the snapshot with the
    given project_id, same workspace and name.
    Returns the alert id or None.
    """
    cursor.execute("""
        SELECT id FROM alerts
        WHERE workspace_id = %s
          AND name = %s
          AND project_id = %s
          AND id != %s
          AND created_at > %s
        LIMIT 1
    """, (workspace_id, name, project_id, original_id, captured_at))
    row = cursor.fetchone()
    return row["id"] if row else None


def count_orphan_alerts(cursor, excluded_workspace_ids=None):
    if excluded_workspace_ids:
        placeholders = ", ".join(["%s"] * len(excluded_workspace_ids))
        cursor.execute(
            f"SELECT COUNT(*) AS cnt FROM alerts "
            f"WHERE project_id IS NULL AND workspace_id NOT IN ({placeholders})",
            excluded_workspace_ids,
        )
    else:
        cursor.execute("SELECT COUNT(*) AS cnt FROM alerts WHERE project_id IS NULL")
    return cursor.fetchone()["cnt"]


def count_scope_configs_on_alert(cursor, alert_id):
    cursor.execute("""
        SELECT COUNT(*) AS cnt
        FROM alert_trigger_configs atc
        JOIN alert_triggers at ON atc.alert_trigger_id = at.id
        WHERE at.alert_id = %s AND atc.config_type = 'scope:project'
    """, (alert_id,))
    return cursor.fetchone()["cnt"]


# ---------------------------------------------------------------------------
# Migration logic  (mirrors AlertProjectMigrationService)
# ---------------------------------------------------------------------------

def collect_raw_scope_project_ids(alert_snap):
    """
    Mirror of collectScopeProjectIds — all project UUIDs across all triggers.
    """
    ids = set()
    for trigger in alert_snap.get("triggers", []):
        ids.update(trigger.get("scope_project_ids", []))
    return ids


def has_default_group_triggers(alert_snap, valid_project_ids_set):
    """
    Mirror of groupTriggersByProject null-key check.
    Returns True if any trigger ends up in the Default Project group, i.e.
    it has NO valid project among its scope:project refs
    (either workspace-wide with no scope at all, or all its scope projects
    were deleted).
    """
    for trigger in alert_snap.get("triggers", []):
        scope_ids = set(trigger.get("scope_project_ids", []))
        valid_for_trigger = scope_ids & valid_project_ids_set
        if not valid_for_trigger:
            return True
    return False


def compute_expected_assignments(alert_snap, valid_project_ids):
    """
    Mirrors executeAlertMigration logic.

    Returns:
        original_project_id  — project_id the original alert row should have
                                (a UUID string or the sentinel "DEFAULT")
        new_alerts           — list of project_ids for which new alert rows
                                must have been created (UUID string or "DEFAULT")
    """
    valid_set = set(valid_project_ids)

    if not valid_project_ids:
        # All projects deleted or alert was workspace-wide
        return "DEFAULT", []

    first_project = valid_project_ids[0]   # already sorted by DB ORDER BY id

    new_alerts = []

    # Workspace-wide / deleted-project triggers → new Default Project alert
    if has_default_group_triggers(alert_snap, valid_set):
        new_alerts.append("DEFAULT")

    # Remaining valid projects → new split alerts
    for pid in valid_project_ids[1:]:
        new_alerts.append(pid)

    return first_project, new_alerts


# ---------------------------------------------------------------------------
# Result tracking
# ---------------------------------------------------------------------------

class Results:
    def __init__(self):
        self.passed = []
        self.failed = []
        self.warnings = []

    def ok(self, msg):
        self.passed.append(msg)
        print(f"  ✓  {msg}")

    def fail(self, msg):
        self.failed.append(msg)
        print(f"  ✗  {msg}")

    def warn(self, msg):
        self.warnings.append(msg)
        print(f"  ⚠  {msg}")

    def summary(self):
        total = len(self.passed) + len(self.failed)
        print()
        print("=" * 64)
        print(f"PASSED  : {len(self.passed)}/{total}")
        print(f"FAILED  : {len(self.failed)}/{total}")
        if self.warnings:
            print(f"WARNINGS: {len(self.warnings)}")
        print("=" * 64)
        if self.failed:
            print("\nFailed checks:")
            for m in self.failed:
                print(f"  ✗  {m}")
        if self.warnings:
            print("\nWarnings:")
            for m in self.warnings:
                print(f"  ⚠  {m}")


# ---------------------------------------------------------------------------
# Per-alert verification
# ---------------------------------------------------------------------------

def verify_alert(cursor, snap, captured_at, default_project_cache, results):
    alert_id = snap["id"]
    workspace_id = snap["workspace_id"]
    name = snap["name"]
    short = f"alert {alert_id[:8]}… (name={name!r})"

    # --- resolve Default Project for this workspace (cached) --------------
    if workspace_id not in default_project_cache:
        default_project_cache[workspace_id] = fetch_default_project_id(cursor, workspace_id)
    default_project_id = default_project_cache[workspace_id]

    # --- collect raw scope project IDs and find valid ones ----------------
    raw_scope_ids = collect_raw_scope_project_ids(snap)
    valid_project_ids = fetch_valid_project_ids(cursor, workspace_id, list(raw_scope_ids))

    # --- compute expected assignments -------------------------------------
    expected_original, expected_new = compute_expected_assignments(snap, valid_project_ids)

    # Resolve "DEFAULT" sentinel to actual UUID
    def resolve(pid):
        if pid == "DEFAULT":
            return default_project_id
        return pid

    expected_original_id = resolve(expected_original)
    expected_new_ids = [resolve(p) for p in expected_new]

    # If Default Project doesn't exist and we need it, that's a problem
    if expected_original == "DEFAULT" and default_project_id is None:
        results.fail(
            f"{short}: expected Default Project but it doesn't exist in workspace {workspace_id}"
        )
        return
    for sentinel, resolved in zip(expected_new, expected_new_ids):
        if sentinel == "DEFAULT" and resolved is None:
            results.fail(
                f"{short}: expected a new Default Project alert but Default Project "
                f"doesn't exist in workspace {workspace_id}"
            )
            return

    # --- check original alert has the expected project_id -----------------
    actual_project_id = fetch_alert_project_id(cursor, alert_id)

    if actual_project_id is None:
        results.fail(f"{short}: project_id is still NULL — not migrated")
        return

    if actual_project_id == expected_original_id:
        label = DEFAULT_PROJECT_NAME if expected_original == "DEFAULT" else expected_original_id[:8] + "…"
        results.ok(f"{short}: original alert → {label} ✓")
    else:
        expected_label = DEFAULT_PROJECT_NAME if expected_original == "DEFAULT" else expected_original_id
        results.fail(
            f"{short}: original alert has project_id={actual_project_id} "
            f"but expected {expected_label}"
        )

    # --- check scope:project configs cleaned up on original alert ---------
    scope_remaining = count_scope_configs_on_alert(cursor, alert_id)
    if scope_remaining == 0:
        results.ok(f"{short}: scope:project configs removed from original alert ✓")
    else:
        results.fail(
            f"{short}: {scope_remaining} scope:project config(s) still present "
            f"on original alert's triggers"
        )

    # --- check each expected new alert ------------------------------------
    for sentinel, project_id in zip(expected_new, expected_new_ids):
        label = DEFAULT_PROJECT_NAME if sentinel == "DEFAULT" else project_id[:8] + "…"
        new_id = fetch_new_alert_with_project(
            cursor, workspace_id, name, project_id, alert_id, captured_at
        )
        if new_id:
            results.ok(
                f"{short}: new alert for {label} found (id={new_id[:8]}…) ✓"
            )
        else:
            results.fail(
                f"{short}: expected a new alert for project {label} "
                f"(created after {captured_at} in workspace {workspace_id} "
                f"with name={name!r}) but none found"
            )

    # --- warn if no splits/new alerts expected but raw scope had projects -
    if not expected_new and raw_scope_ids and not valid_project_ids:
        results.warn(
            f"{short}: all {len(raw_scope_ids)} referenced project(s) are gone from DB — "
            f"assigned to Default Project as expected, but referenced projects no longer exist"
        )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Verify alert migration against snapshot.")
    parser.add_argument("snapshot", help="Path to snapshot JSON from snapshot.py")
    args = parser.parse_args()

    with open(args.snapshot) as f:
        snapshot = json.load(f)

    captured_at = snapshot.get("db_captured_at") or snapshot["captured_at"]
    alerts = snapshot["alerts"]
    total = snapshot["total_orphan_alerts"]

    print(f"Snapshot  : {args.snapshot}")
    print(f"Captured  : {captured_at}")
    print(f"Alerts    : {total}")
    print(f"Workspaces: {snapshot['workspaces_affected']}")

    if total == 0:
        print("\nSnapshot contains 0 orphan alerts — nothing to verify.")
        sys.exit(0)

    excluded_raw = os.environ.get("MIGRATION_EXCLUDED_WORKSPACE_IDS", "")
    excluded_workspace_ids = [w.strip() for w in excluded_raw.split(",") if w.strip()]

    print("\nConnecting to database...")
    conn = connect()
    results = Results()
    default_project_cache = {}

    try:
        with conn.cursor() as cursor:
            print("\n[Global]")
            if excluded_workspace_ids:
                print(f"  Excluding {len(excluded_workspace_ids)} workspace(s) "
                      f"from orphan count (MIGRATION_EXCLUDED_WORKSPACE_IDS)")
            orphan_count = count_orphan_alerts(cursor, excluded_workspace_ids)
            if orphan_count == 0:
                results.ok("No alerts with project_id IS NULL remain in the database")
            else:
                results.fail(
                    f"{orphan_count} alert(s) still have project_id IS NULL — "
                    f"migration incomplete or new orphan alerts inserted after snapshot"
                )

            print(f"\n[Per-alert] Checking {total} alert(s)...")
            for snap in alerts:
                verify_alert(cursor, snap, captured_at, default_project_cache, results)
    finally:
        conn.close()

    results.summary()
    sys.exit(0 if not results.failed else 1)


if __name__ == "__main__":
    main()
