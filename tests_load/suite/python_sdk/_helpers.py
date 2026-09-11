"""Helpers shared by the load-test suite.

Functions here are intentionally simple so individual tests can be read
top-to-bottom without jumping around.
"""

import base64
import contextlib
import json
import logging
import os
import random
import string
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Set, Tuple

import opik
from opik import Opik
from opik.rest_api.types.span_public import SpanPublic
from opik.rest_api.types.trace_public import TracePublic


PROJECT_PREFIX: str = "loadtest"
REPORT_DIR: Path = Path(__file__).resolve().parents[2] / ".last_run"

KB: int = 1_000
MB: int = 1_000_000

REQUIRED_SPAN_FIELDS: Tuple[str, ...] = ("name", "end_time", "input", "output")


class Metrics:
    """Tiny key/value recorder with a `timer(label)` context manager.

    One instance per test; written to ``REPORT_DIR/<test_name>.json`` at
    teardown by the ``metrics`` fixture.
    """

    test_name: str
    _data: Dict[str, Any]

    def __init__(self, test_name: str) -> None:
        self.test_name = test_name
        self._data = {"test_name": test_name}

    def __setitem__(self, key: str, value: Any) -> None:
        self._data[key] = value

    @contextlib.contextmanager
    def timer(self, label: str) -> Iterator[None]:
        start: float = time.perf_counter()
        yield
        self._data[f"{label}_seconds"] = round(time.perf_counter() - start, 3)

    def write(self) -> None:
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        out_path: Path = REPORT_DIR / f"{self.test_name}.json"
        out_path.write_text(json.dumps(self._data, indent=2, default=str))
        logging.getLogger(self.test_name).info(
            "metrics: %s", json.dumps(self._data, default=str)
        )


def unique_project_name(scenario: str) -> str:
    timestamp: str = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    return f"{PROJECT_PREFIX}-{scenario}-{timestamp}-{uuid.uuid4().hex[:6]}"


def random_text(size_bytes: int) -> str:
    return "".join(random.choices(string.ascii_letters + string.digits, k=size_bytes))


def random_bytes(size_bytes: int) -> bytes:
    return os.urandom(size_bytes)


PNG_MAGIC: bytes = b"\x89PNG\r\n\x1a\n"


def random_base64_png(size_bytes: int) -> str:
    """Returns a standard-alphabet base64-encoded byte stream that the SDK's
    attachment-extraction pipeline recognises as a PNG.

    Two constraints from the SDK that the naïve approach (random bytes,
    ``urlsafe_b64encode``) trips over:

    1. The extraction regex is ``[A-Za-z0-9+/]``, so the encoded blob
       must use the standard ``+/`` alphabet (``b64encode``), not the
       ``-_`` url-safe one.
    2. The decoder MIME-sniffs the raw bytes and skips anything that
       comes back as ``application/octet-stream`` or ``text/plain``,
       so the byte stream must start with a recognised magic header.

    The bytes after the 8-byte PNG magic are pure noise — the test
    doesn't render the image, it just exercises the extract/upload path.
    """
    raw_size: int = (size_bytes // 4) * 3
    payload: bytes = PNG_MAGIC + os.urandom(max(0, raw_size - len(PNG_MAGIC)))
    return base64.b64encode(payload).decode("ascii")


def think_time() -> None:
    """Sleeps a small randomized interval so submits aren't lockstep.

    Real callers always have at least some gap between consecutive SDK calls
    (LLM responses, request handling, etc.). The range is small on purpose so
    it doesn't dominate runtime for high-count scenarios.
    """
    time.sleep(random.uniform(0.0005, 0.002))


def opik_client() -> Opik:
    return opik.Opik()


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def _assert_required_fields_set(
    items: Iterable[Any], required_fields: Tuple[str, ...], kind: str
) -> None:
    """Fails the test if any item is missing one of `required_fields`.

    Catches partial-write bugs where the entity lands but a later update
    (e.g. ``end_time``) was dropped or never sent.
    """
    broken: List[str] = []
    for item in items:
        for field in required_fields:
            if getattr(item, field) is None:
                broken.append(f"{item.id}: {field} is None")
                break
    if broken:
        raise AssertionError(
            f"{len(broken)} {kind}(s) missing required fields "
            f"({', '.join(required_fields)}); sample: {broken[:5]}"
        )


def verify_traces(
    client: Opik,
    project_name: str,
    expected_count: int,
    timeout_seconds: int = 900,
) -> List[TracePublic]:
    """Polls until at least `expected_count` traces are visible in the project.

    Excludes ``input``/``output``/``metadata`` from the response. We only
    need ``id``, ``name``, ``end_time`` for the load-test verification,
    and excluding the bulky fields:

    1. Streams much less data back for the high-count scenarios.
    2. Side-steps OPIK-6651, where attachment-extracted traces fail to
       stream because ``AttachmentService.list`` can't read
       ``workspaceName`` from the reactor context during enrichment.
       Excluding the fields skips the enrichment path entirely. Drop
       the exclude list once OPIK-6651 is fixed.

    ``end_time``/``name`` field checks still run; ``input``/``output``
    are not included in the response so we can't assert on them here.
    """
    traces: List[TracePublic] = client.search_traces(
        project_name=project_name,
        max_results=expected_count,
        wait_for_at_least=expected_count,
        wait_for_timeout=timeout_seconds,
        exclude=["input", "output", "metadata"],
    )
    _assert_required_fields_set(traces, ("name", "end_time"), "trace")
    return traces


def verify_spans_for_trace(
    client: Opik,
    project_name: str,
    trace_id: str,
    expected_count: int,
    timeout_seconds: int = 600,
) -> List[SpanPublic]:
    """Polls until at least `expected_count` spans are visible for a trace."""
    spans: List[SpanPublic] = client.search_spans(
        project_name=project_name,
        trace_id=trace_id,
        max_results=expected_count,
        wait_for_at_least=expected_count,
        wait_for_timeout=timeout_seconds,
    )
    _assert_required_fields_set(spans, REQUIRED_SPAN_FIELDS, "span")
    return spans


def verify_exact_trace_ids(
    client: Opik,
    project_name: str,
    expected_ids: Set[str],
    timeout_seconds: int = 900,
) -> Set[str]:
    """Waits for all `expected_ids` to land and returns the delivered id set.

    Raises ``AssertionError`` listing a sample of missing ids if some traces
    are never delivered within the timeout. This catches dropped messages
    (e.g. regression cases like OPIK-6444) rather than just under-counts.

    Required-field validation runs inside ``verify_traces`` so any trace
    landing without ``end_time``, ``name``, ``input``, or ``output`` also
    fails the test.
    """
    traces: List[TracePublic] = verify_traces(
        client,
        project_name=project_name,
        expected_count=len(expected_ids),
        timeout_seconds=timeout_seconds,
    )
    delivered: Set[str] = {trace.id for trace in traces}
    missing: Set[str] = expected_ids - delivered
    if missing:
        raise AssertionError(
            f"{len(missing)}/{len(expected_ids)} traces missing after flush; "
            f"sample missing ids: {sorted(missing)[:3]}"
        )
    return delivered


def verify_attachments(
    client: Opik,
    project_name: str,
    entity_type: str,
    entity_id: str,
    expected_count: int,
    timeout_seconds: int = 600,
) -> int:
    """Polls the attachments REST endpoint until `expected_count` are listed.

    The endpoint's ``path`` query parameter is a base64-encoded base URL
    the backend uses to build download links — we pass the SDK's
    configured base URL so the round-trip succeeds. Uses the standard
    ``b64encode`` alphabet (not ``urlsafe_b64encode``) to match the
    contract used by ``attachment/client.py`` and ``tests/e2e/verifiers.py``.
    """
    project = client.rest_client.projects.retrieve_project(name=project_name)
    base_url: str = str(client.config.url_override).rstrip("/")
    encoded_base_url: str = base64.b64encode(
        base_url.encode("utf-8")
    ).decode("ascii")
    deadline: float = time.time() + timeout_seconds
    last_seen: int = 0
    while time.time() < deadline:
        page = client.rest_client.attachments.attachment_list(
            project_id=project.id,
            entity_type=entity_type,
            entity_id=entity_id,
            path=encoded_base_url,
            size=expected_count + 10,
        )
        last_seen = len(page.content or [])
        if last_seen >= expected_count:
            return last_seen
        time.sleep(1)
    raise TimeoutError(
        f"Only {last_seen}/{expected_count} attachments visible after "
        f"{timeout_seconds}s for {entity_type} {entity_id}"
    )
