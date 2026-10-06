"""Unit tests for trace id generation during ``opik import``.

The importer never reuses the exported trace id: ids must stay unique across
projects and workspaces, and a reused id also carries the original UUIDv7
timestamp, which the server rejects once it falls outside the ingestion window
(``too_old``). These tests pin the resulting contract:

- a trace id is always minted from the current time, so an import of arbitrarily
  old data stays inside the window;
- traces are created in the order the source project listed them, so the
  destination trace list and thread view keep that order;
- the exported id survives as ``_import_id`` metadata, and spans/parent spans
  are remapped onto the new ids.

The experiment importer recreates traces through its own path, so the id and
ordering contract is pinned there too.
"""

import json
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional
from unittest.mock import MagicMock, patch

import pytest


from opik import id_helpers
from opik.api_objects import helpers
from opik.cli.imports.experiment import _import_traces_for_project
from opik.cli.imports.project import import_traces_from_directory
from opik.cli.imports.utils import (
    as_metadata_object,
    build_import_metadata,
    sort_trace_files_chronologically,
)

_PROJECT = "dest-project"


def _uuid7_timestamp(value: str) -> datetime:
    """The event time encoded in the leading 48 bits of a UUIDv7."""
    return datetime.fromtimestamp((uuid.UUID(value).int >> 80) / 1000, tz=timezone.utc)


def _floor_ms(moment: datetime) -> datetime:
    """Truncate to the millisecond resolution a UUIDv7 timestamp is stored at.

    Comparing a bound at finer resolution reads a truncation as a clock going
    backwards.
    """
    return moment.replace(microsecond=(moment.microsecond // 1000) * 1000)


def _assert_minted_now(trace_id: str, before: datetime, after: datetime) -> None:
    """Assert the id was minted from the wall clock during the import.

    The upper bound carries a second of slack: uuid6 keeps generation monotonic
    by pushing the embedded timestamp a millisecond past the last one it handed
    out, so a burst of ids can run slightly ahead of the clock. The timestamps
    this is distinguishing it from sit days in the past, so the slack costs the
    assertion nothing.
    """
    assert (
        _floor_ms(before) <= _uuid7_timestamp(trace_id) <= after + timedelta(seconds=1)
    )


@contextmanager
def _reversed_trace_glob() -> Iterator[None]:
    """Make ``Path.glob`` hand trace files back newest first.

    ``glob`` yields filesystem order, which is arbitrary but happens to already
    be creation order on the directories these tests build. Reversing it is the
    worst case the importer has to sort back into shape.
    """
    original_glob = Path.glob

    def reversed_glob(self: Path, pattern: str):
        results = list(original_glob(self, pattern))
        return reversed(sorted(results)) if pattern == "trace_*.json" else results

    with patch.object(Path, "glob", reversed_glob):
        yield


def _write_trace_file(
    project_dir: Path,
    trace_id: str,
    start_time: datetime,
    *,
    name: str = "imported_trace",
    metadata: Optional[Any] = None,
    spans: Optional[List[Dict[str, Any]]] = None,
) -> Path:
    """Write one exported trace file in the layout ``opik export`` produces."""
    trace_file = project_dir / f"trace_{trace_id}.json"
    trace_file.write_text(
        json.dumps(
            {
                "trace": {
                    "id": trace_id,
                    "name": name,
                    "start_time": start_time.isoformat(),
                    "end_time": (start_time + timedelta(seconds=1)).isoformat(),
                    "input": {"question": "q"},
                    "output": {"answer": "a"},
                    "metadata": metadata,
                    "thread_id": "thread-1",
                },
                "spans": spans or [],
                "attachments": [],
            }
        )
    )
    return trace_file


class _RecordingClient:
    """Test double that reproduces the parts of the client these tests depend on.

    Two behaviours are delegated rather than faked, because a test that only
    recorded kwargs would pass while the real client raised:

    - ``Opik.trace`` and ``Opik.span`` mint a fresh UUIDv7 when the caller passes
      no id, and honour one that is passed. That precedence is what makes these
      tests fail if the importer ever starts supplying an id of its own again.
    - ``Opik.span`` merges usage into the span's metadata through
      ``helpers.add_usage_to_metadata``, which unpacks the metadata and so raises
      on anything that is not a mapping. Calling the real helper is what makes a
      bad metadata shape surface here instead of at runtime.
    """

    def __init__(self) -> None:
        self.traces: List[Dict[str, Any]] = []
        self.spans: List[Dict[str, Any]] = []
        self.flush = MagicMock(return_value=True)
        self.queue_attachment_upload = MagicMock()

    def trace(self, **kwargs: Any) -> MagicMock:
        trace_id = kwargs.get("id") or id_helpers.generate_id()
        self.traces.append({**kwargs, "id": trace_id})
        return MagicMock(id=trace_id)

    def span(self, **kwargs: Any) -> MagicMock:
        metadata = helpers.add_usage_to_metadata(
            usage=kwargs.get("usage"), metadata=kwargs.get("metadata")
        )
        span_id = kwargs.get("id") or id_helpers.generate_id()
        self.spans.append({**kwargs, "id": span_id, "metadata": metadata})
        return MagicMock(id=span_id)


def _import_via_traces(project_dir: Path) -> _RecordingClient:
    """Import through ``opik import ... traces``."""
    client = _RecordingClient()
    stats = import_traces_from_directory(
        client,
        project_dir,
        _PROJECT,
        dry_run=False,
        name_pattern=None,
        debug=False,
    )
    assert stats["traces_errors"] == 0
    return client


def _import_via_experiment(project_dir: Path) -> _RecordingClient:
    """Import through ``opik import ... experiment``, which recreates traces itself."""
    client = _RecordingClient()
    _, stats = _import_traces_for_project(
        client,
        project_dir,
        _PROJECT,
        dry_run=False,
        debug=False,
    )
    assert stats["traces_errors"] == 0
    return client


# The two CLI paths that recreate traces from an export. They do not share the
# code that mints ids, orders the files or records provenance, so the contract
# below is asserted against each.
_IMPORTERS = [
    pytest.param(_import_via_traces, id="traces"),
    pytest.param(_import_via_experiment, id="experiment"),
]

_AGED = timedelta(days=30)


@pytest.mark.parametrize("run_import", _IMPORTERS)
class TestImportedTraceIdContract:
    def test_import__trace_older_than_the_ingestion_window__id_carries_current_time(
        self, tmp_path: Path, run_import: Any
    ) -> None:
        _write_trace_file(
            tmp_path,
            str(id_helpers.generate_id()),
            datetime.now(timezone.utc) - _AGED,
        )

        before = datetime.now(timezone.utc)
        client = run_import(tmp_path)
        after = datetime.now(timezone.utc)

        (trace,) = client.traces
        _assert_minted_now(trace["id"], before, after)

    def test_import__exported_trace__records_source_id_beside_existing_metadata(
        self, tmp_path: Path, run_import: Any
    ) -> None:
        source_id = str(id_helpers.generate_id())
        _write_trace_file(
            tmp_path,
            source_id,
            datetime.now(timezone.utc) - _AGED,
            metadata={"existing": "kept"},
        )

        (trace,) = run_import(tmp_path).traces

        assert trace["metadata"]["_import_id"] == source_id
        assert trace["metadata"]["existing"] == "kept"
        assert trace["id"] != source_id

    @pytest.mark.parametrize("metadata", [[{"tagged": True}], "a bare string"])
    def test_import__non_object_trace_metadata__nested_under_import_metadata(
        self, tmp_path: Path, run_import: Any, metadata: Any
    ) -> None:
        """A trace written through the REST API can hold an array or a scalar."""
        source_id = str(id_helpers.generate_id())
        _write_trace_file(
            tmp_path,
            source_id,
            datetime.now(timezone.utc) - _AGED,
            metadata=metadata,
        )

        (trace,) = run_import(tmp_path).traces

        assert trace["metadata"]["_import_metadata"] == metadata
        assert trace["metadata"]["_import_id"] == source_id

    @pytest.mark.parametrize(
        "usage",
        [{"prompt_tokens": 1}, None],
        ids=["with-usage", "without-usage"],
    )
    def test_import__span_with_non_object_metadata__metadata_resolved_to_a_mapping(
        self, tmp_path: Path, run_import: Any, usage: Optional[Dict[str, Any]]
    ) -> None:
        """The SDK merges a span's usage into its metadata by unpacking it.

        Handing it anything but a mapping raises before the span is queued, so
        the importer resolves the shape rather than passing it through — and
        does so whether or not this particular span carries usage, since a
        value's shape should not depend on an unrelated field.
        """
        start = datetime.now(timezone.utc) - _AGED
        span_info = {
            "id": "src-span",
            "name": "llm",
            "start_time": start.isoformat(),
            "metadata": ["not an object"],
        }
        if usage is not None:
            span_info["usage"] = usage
        _write_trace_file(
            tmp_path, str(id_helpers.generate_id()), start, spans=[span_info]
        )

        (span,) = run_import(tmp_path).spans

        assert span["metadata"]["_import_metadata"] == ["not an object"]

    def test_import__files_yielded_out_of_order__new_ids_follow_source_order(
        self, tmp_path: Path, run_import: Any
    ) -> None:
        base = datetime.now(timezone.utc) - timedelta(days=10)
        source_ids = [
            str(id_helpers.generate_id(timestamp=base + timedelta(minutes=minute)))
            for minute in range(10)
        ]
        for position, source_id in enumerate(source_ids):
            _write_trace_file(tmp_path, source_id, base + timedelta(minutes=position))

        with _reversed_trace_glob():
            client = run_import(tmp_path)

        assert [
            trace["metadata"]["_import_id"] for trace in client.traces
        ] == source_ids
        new_ids = [trace["id"] for trace in client.traces]
        assert new_ids == sorted(new_ids)


class TestTracesImporter:
    """Behaviour of ``opik import ... traces``, where the derived id was removed."""

    def test_import_traces__exported_trace__preserves_original_start_and_end_time(
        self, tmp_path: Path
    ) -> None:
        original_start = datetime.now(timezone.utc) - _AGED
        _write_trace_file(tmp_path, str(id_helpers.generate_id()), original_start)

        (trace,) = _import_via_traces(tmp_path).traces

        assert trace["start_time"] == original_start
        assert trace["end_time"] == original_start + timedelta(seconds=1)

    def test_import_traces__trace_without_start_time__id_still_carries_current_time(
        self, tmp_path: Path
    ) -> None:
        """The removed code derived the id from start_time, which can be absent."""
        trace_file = tmp_path / f"trace_{id_helpers.generate_id()}.json"
        trace_file.write_text(json.dumps({"trace": {"id": "src"}, "spans": []}))

        before = datetime.now(timezone.utc)
        client = _import_via_traces(tmp_path)
        after = datetime.now(timezone.utc)

        (trace,) = client.traces
        _assert_minted_now(trace["id"], before, after)

    def test_import_traces__nested_spans__reattached_to_the_new_trace_and_parent_ids(
        self, tmp_path: Path
    ) -> None:
        start = datetime.now(timezone.utc) - _AGED
        _write_trace_file(
            tmp_path,
            str(id_helpers.generate_id()),
            start,
            spans=[
                {
                    "id": "src-child",
                    "name": "child",
                    "parent_span_id": "src-parent",
                    "start_time": start.isoformat(),
                },
                {"id": "src-parent", "name": "parent", "start_time": start.isoformat()},
            ],
        )

        client = _import_via_traces(tmp_path)

        (trace,) = client.traces
        parent, child = (
            next(span for span in client.spans if span["name"] == name)
            for name in ("parent", "child")
        )
        assert {span["trace_id"] for span in client.spans} == {trace["id"]}
        assert child["parent_span_id"] == parent["id"]
        assert parent["parent_span_id"] is None
        assert {parent["id"], child["id"]}.isdisjoint({"src-parent", "src-child"})


class TestTraceFileOrdering:
    def test_sort_trace_files_chronologically__shuffled__orders_by_embedded_id(
        self, tmp_path: Path
    ) -> None:
        base = datetime.now(timezone.utc) - timedelta(days=10)
        files = [
            tmp_path
            / f"trace_{id_helpers.generate_id(timestamp=base + timedelta(minutes=minute))}.json"
            for minute in range(10)
        ]

        assert sort_trace_files_chronologically(list(reversed(files))) == files


class TestImportMetadata:
    """These helpers are what guarantee the shape everything else needs."""

    @pytest.mark.parametrize("metadata", [[{"a": 1}], "bare", 42, []])
    def test_as_metadata_object__non_object__nested_under_import_metadata(
        self, metadata: Any
    ) -> None:
        assert as_metadata_object(metadata) == {"_import_metadata": metadata}

    @pytest.mark.parametrize("metadata", [{"a": 1}, {}, None])
    def test_as_metadata_object__object_or_none__returned_as_is(
        self, metadata: Any
    ) -> None:
        assert as_metadata_object(metadata) is metadata

    @pytest.mark.parametrize("metadata", [[{"a": 1}], "bare", 42, []])
    def test_build_import_metadata__non_object__nested_under_import_metadata(
        self, metadata: Any
    ) -> None:
        result = build_import_metadata({"id": "src"}, ["id"], metadata)

        assert result == {"_import_metadata": metadata, "_import_id": "src"}

    def test_build_import_metadata__nothing_to_add__non_object_still_nested(
        self,
    ) -> None:
        """The export need not carry the fields, so this branch is reachable."""
        assert build_import_metadata({}, ["id"], ["x"]) == {"_import_metadata": ["x"]}

    def test_build_import_metadata__no_metadata_and_nothing_to_add__stays_none(
        self,
    ) -> None:
        assert build_import_metadata({}, ["id"], None) is None
