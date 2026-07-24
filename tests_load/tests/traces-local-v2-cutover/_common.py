"""Shared helpers for the local traces-cutover simulation scripts.

These scripts stand up a representative dataset and live traffic on a *local* Opik so the traces buffered-cutover runbook
(apps/opik-backend/data-migrations/traces-local-v2-cutover) can be rehearsed end to end. They are ad-hoc CLI tools, not
pytest suites.

ClickHouse is reached directly (over HTTP) because the seeder must backdate `created_at`, which the ingestion API treats
as read-only — the SDK cannot produce multi-week history. Start Opik with `./opik.sh --port-mapping` so ClickHouse is on
localhost:8123. The SDK-based traffic scripts use the normal APIs and need only `OPIK_URL_OVERRIDE`.
"""

import logging
import os
import time
from datetime import datetime, timezone

import clickhouse_connect
import opik
from opik import id_helpers

logging.basicConfig(level=logging.INFO, format="%(levelname)s [%(asctime)s]: %(message)s")
LOGGER = logging.getLogger("cutover")

DEFAULT_PROJECT = "cutover-load-test"


def make_ch_client():
    """ClickHouse client for the local docker-compose analytics DB (defaults match `opik.sh --port-mapping`)."""
    return clickhouse_connect.get_client(
        host=os.environ.get("OPIK_CH_HOST", "localhost"),
        port=int(os.environ.get("OPIK_CH_PORT", "8123")),
        username=os.environ.get("OPIK_CH_USER", "opik"),
        password=os.environ.get("OPIK_CH_PASSWORD", "opik"),
        database=os.environ.get("OPIK_CH_DATABASE", "opik"),
    )


def make_opik_client() -> opik.Opik:
    """SDK client. Reads OPIK_URL_OVERRIDE etc. from the environment, as the SDK normally does."""
    return opik.Opik()


def mint_uuid7(at: datetime) -> str:
    """A UUIDv7 whose embedded timestamp is `at` — the backend derives `id_at` (the destination partition) from it."""
    return id_helpers.generate_id(at)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def discover_workspace_and_project(
    opik_client: opik.Opik, ch, project_name: str, timeout_s: int = 60
) -> tuple[str, str]:
    """Return the ClickHouse `(workspace_id, project_id)` for `project_name`.

    Logs one anchor trace through the SDK (which creates the project if needed), then reads that row back from
    ClickHouse — so the seeder writes history into the same project the SDK traffic scripts target, without hardcoding
    the workspace id.
    """
    anchor_id = mint_uuid7(utcnow())
    opik_client.trace(id=anchor_id, name="cutover-anchor", project_name=project_name, input={"anchor": True}).end()
    opik_client.flush()

    deadline = time.time() + timeout_s
    while time.time() < deadline:
        rows = ch.query(
            "SELECT workspace_id, toString(project_id) FROM traces WHERE id = {id:String} LIMIT 1",
            parameters={"id": anchor_id},
        ).result_rows
        if rows:
            workspace_id, project_id = rows[0]
            LOGGER.info("Resolved project '%s': workspace_id=%s project_id=%s", project_name, workspace_id, project_id)
            return workspace_id, project_id
        time.sleep(0.5)
    raise TimeoutError(f"anchor trace for project '{project_name}' did not appear in ClickHouse within {timeout_s}s")
