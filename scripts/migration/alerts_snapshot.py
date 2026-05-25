#!/usr/bin/env python3
"""
Pre-migration snapshot.

Captures all orphan alerts (project_id IS NULL) with their triggers and trigger
configs and writes raw DB state to a timestamped JSON file.
No migration logic — pure data capture.

Run BEFORE the AlertProjectMigrationJob processes anything.

Usage:
    pip install pymysql
    DB_HOST=... DB_PORT=3306 DB_NAME=opik DB_USER=... DB_PASSWORD=... python snapshot.py
    python snapshot.py --output my_snapshot.json   # custom output path
"""

import argparse
import json
import os
import sys
from datetime import datetime, timezone

try:
    import pymysql
    import pymysql.cursors
except ImportError:
    print("ERROR: pymysql not installed. Run: pip install pymysql")
    sys.exit(1)


def connect():
    return pymysql.connect(
        host=os.environ.get("DB_HOST", "mysql-host"),
        port=int(os.environ.get("DB_PORT", "3306")),
        database=os.environ.get("DB_NAME", "db_name"),
        user=os.environ.get("DB_USER", "user"),
        password=os.environ.get("DB_PASSWORD", "password"),
        cursorclass=pymysql.cursors.DictCursor,
        charset="utf8mb4",
    )



def fetch_orphan_alerts(cursor, excluded_workspace_ids=None):
    if excluded_workspace_ids:
        placeholders = ", ".join(["%s"] * len(excluded_workspace_ids))
        cursor.execute(
            f"""
            SELECT id, name, enabled, workspace_id, webhook_id,
                   created_by, last_updated_by, created_at, last_updated_at
            FROM alerts
            WHERE project_id IS NULL
              AND workspace_id NOT IN ({placeholders})
            ORDER BY workspace_id, id
            """,
            excluded_workspace_ids,
        )
    else:
        cursor.execute("""
            SELECT id, name, enabled, workspace_id, webhook_id,
                   created_by, last_updated_by, created_at, last_updated_at
            FROM alerts
            WHERE project_id IS NULL
            ORDER BY workspace_id, id
        """)
    return cursor.fetchall()


def fetch_triggers(cursor, alert_id):
    cursor.execute("""
        SELECT id, alert_id, event_type, created_by, created_at
        FROM alert_triggers
        WHERE alert_id = %s
        ORDER BY id
    """, (alert_id,))
    return cursor.fetchall()


def fetch_trigger_configs(cursor, trigger_id):
    cursor.execute("""
        SELECT id, alert_trigger_id, config_type, config_value,
               group_index, created_by, last_updated_by, created_at, last_updated_at
        FROM alert_trigger_configs
        WHERE alert_trigger_id = %s
        ORDER BY id
    """, (trigger_id,))
    return cursor.fetchall()


def extract_scope_project_ids(configs):
    """Return all project UUIDs listed in scope:project trigger configs."""
    ids = []
    for cfg in configs:
        if cfg.get("config_type") != "scope:project":
            continue
        raw = cfg.get("config_value")
        if not raw:
            continue
        # pymysql returns JSON columns as Python objects; guard against raw strings too
        parsed = raw if isinstance(raw, dict) else json.loads(raw)
        project_ids_value = parsed.get("project_ids", "[]")
        try:
            project_ids = (
                json.loads(project_ids_value)
                if isinstance(project_ids_value, str)
                else project_ids_value
            )
            ids.extend(project_ids)
        except (json.JSONDecodeError, TypeError):
            pass
    return ids


def serialize(v):
    return v.isoformat() if hasattr(v, "isoformat") else v


def row_to_dict(row):
    return {k: serialize(v) for k, v in row.items()}


def main():
    parser = argparse.ArgumentParser(description="Snapshot orphan alerts before migration.")
    parser.add_argument("--output", help="Output file (default: auto-timestamped)")
    args = parser.parse_args()

    output_file = args.output or (
        f"alert_migration_snapshot_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}.json"
    )

    excluded_raw = os.environ.get("MIGRATION_EXCLUDED_WORKSPACE_IDS", "")
    excluded_workspace_ids = [w.strip() for w in excluded_raw.split(",") if w.strip()]

    print("Connecting to database...")
    conn = connect()

    try:
        with conn.cursor() as cursor:
            if excluded_workspace_ids:
                print(f"Excluding {len(excluded_workspace_ids)} workspace(s) "
                      f"(MIGRATION_EXCLUDED_WORKSPACE_IDS)")
            print("Fetching orphan alerts (project_id IS NULL)...")
            alerts = fetch_orphan_alerts(cursor, excluded_workspace_ids)
            print(f"  Found {len(alerts)} orphan alert(s)")

            workspace_counts = {}
            alert_records = []

            for alert in alerts:
                alert_id = alert["id"]
                ws_id = alert["workspace_id"]
                workspace_counts[ws_id] = workspace_counts.get(ws_id, 0) + 1

                triggers = fetch_triggers(cursor, alert_id)
                trigger_records = []

                for trigger in triggers:
                    configs = fetch_trigger_configs(cursor, trigger["id"])
                    scope_ids = extract_scope_project_ids(configs)
                    trigger_records.append({
                        **row_to_dict(trigger),
                        "configs": [row_to_dict(c) for c in configs],
                        # project UUIDs from scope:project configs for this trigger
                        "scope_project_ids": scope_ids,
                    })

                alert_records.append({
                    **row_to_dict(alert),
                    "triggers": trigger_records,
                })

        snapshot = {
            "captured_at": datetime.now(timezone.utc).isoformat(),
            "total_orphan_alerts": len(alert_records),
            "workspaces_affected": len(workspace_counts),
            "workspace_summary": {ws: cnt for ws, cnt in sorted(workspace_counts.items())},
            "alerts": alert_records,
        }

        with open(output_file, "w") as f:
            json.dump(snapshot, f, indent=2)

        print(f"\nWorkspaces affected: {len(workspace_counts)}")
        for ws, cnt in sorted(workspace_counts.items()):
            print(f"  {ws}: {cnt} alert(s)")
        print(f"\nSnapshot saved → {output_file}")
        print(f"After migration, run: python verify.py {output_file}")

    finally:
        conn.close()


if __name__ == "__main__":
    main()
