"""Dataset-items upload scenarios.

Each ``Dataset.insert()`` call creates a new dataset version on the
backend; the BE snapshots the previous version's items into the new
version via a ClickHouse ``INSERT … SELECT`` (``COPY_VERSION_ITEMS``).
On multi-replica ClickHouse deployments that SELECT can non-
deterministically return short, truncating the new version's row set;
every subsequent version then cascades off the truncated baseline.
Loss is purely server-side — single-thread sequential REST calls
already trigger it.

These tests can't *reproduce* the bug on a single-replica localhost
install (Notion: "Dataset migration replay: silent data loss on the
version chain"), but they:

1. Provide a green baseline for environments where the bug can fire
   (production, multi-replica staging) — running the suite there will
   surface any short-COPY by way of the item-count assertion.
2. Cover that ``Dataset.insert()`` + ``Dataset.get_items()`` round-trip
   cleanly across many sequential versions on a single thread.
3. Stay on the public, high-level API (``Dataset.insert`` /
   ``Dataset.get_items``) rather than the lower-level REST client the
   ``opik migrate dataset`` tool uses internally.
"""

from typing import Any, Dict, List

from opik import Opik

from . import _helpers
from ._helpers import KB, Metrics


def test_dataset_insert_many_versions(metrics: Metrics, load_scale: float) -> None:
    """Sequential ``Dataset.insert()`` calls, single thread, many versions.

    Mirrors the shape of the production repro from the Notion writeup
    "Dataset migration replay: silent data loss on the version chain":
    one dataset, many versions, modest payload per item, no client-side
    concurrency. The test asserts that the dataset's latest version
    streams back exactly the expected total — i.e. that no
    ``COPY_VERSION_ITEMS`` truncation happened anywhere along the chain.

    Volume at ``load_scale=1.0``:
    - 50 versions × 50 items per version = 2500 items
    - ~4 KB payload per item

    Verifies via ``dataset.get_items()`` (which streams the latest
    version's items, equivalent to ``stream_dataset_items`` with the
    latest version hash) that the delivered count matches the expected
    total. Catches both the metadata-vs-storage disagreement noted in
    the repro (where ``items_total`` reports N but the stream returns
    fewer) and the cascading truncation pattern.
    """
    versions: int = int(50 * load_scale)
    items_per_version: int = 50
    item_payload_bytes: int = 4 * KB
    expected_total: int = versions * items_per_version
    dataset_name: str = _helpers.unique_project_name("dataset-insert")

    metrics["dataset_name"] = dataset_name
    metrics["versions"] = versions
    metrics["items_per_version"] = items_per_version
    metrics["item_payload_bytes"] = item_payload_bytes
    metrics["expected_total_items"] = expected_total

    client: Opik = _helpers.opik_client()
    dataset = client.create_dataset(name=dataset_name)

    with metrics.timer("insert"):
        for _ in range(versions):
            items: List[Dict[str, Any]] = [
                {
                    "input": _helpers.random_text(item_payload_bytes),
                    "expected_output": _helpers.random_text(100),
                }
                for _ in range(items_per_version)
            ]
            dataset.insert(items)

    with metrics.timer("verify"):
        delivered_items: List[Dict[str, Any]] = dataset.get_items()

    metrics["delivered_item_count"] = len(delivered_items)
    assert len(delivered_items) == expected_total, (
        f"Dataset items lost: expected {expected_total}, got {len(delivered_items)}. "
        "Likely a server-side COPY_VERSION_ITEMS truncation on multi-replica "
        "ClickHouse — see Notion 'Dataset migration replay: silent data loss "
        "on the version chain' for the failure mode."
    )
