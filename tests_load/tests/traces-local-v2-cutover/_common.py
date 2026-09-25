"""This suite's bindings for the shared cutover helpers in `tests_load/tests/cutover_common`.

Only what differs per suite lives here — the project name, and the path bootstrap the sibling package needs (these are
run as plain scripts, so only their own directory is on `sys.path`). Everything else is re-exported unchanged, so the
suite scripts import from `_common` and never name the shared package.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cutover_common import (  # noqa: E402  (must follow the sys.path bootstrap above)
    BAD_ID_INSTANT,
    LOGGER,
    discover_workspace_and_project,
    json_payload,
    make_ch_client,
    make_opik_client,
    mint_uuid7,
    ns_ticks,
    random_text,
    us_ticks,
    utcnow,
)
from cutover_common.delete_traffic import build_delete_traffic_command  # noqa: E402

__all__ = [
    "BAD_ID_INSTANT",
    "DEFAULT_PROJECT",
    "LOGGER",
    "build_delete_traffic_command",
    "discover_workspace_and_project",
    "json_payload",
    "make_ch_client",
    "make_opik_client",
    "mint_uuid7",
    "ns_ticks",
    "random_text",
    "us_ticks",
    "utcnow",
]

DEFAULT_PROJECT = "cutover-load-test"
