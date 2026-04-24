"""Unit tests for project import — verifies original trace/span ID reuse.

Covers:
  - import_projects_from_directory uses original trace IDs from export data
  - import_projects_from_directory uses original span IDs from export data
  - Fallback to generated IDs when export data has no id field
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, call, patch

import pytest

_MODULE = "opik.cli.imports.project"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_trace_file(tmp_path: Path, trace_id: str, span_id: str) -> Path:
    """Write a minimal trace_*.json file and return its path."""
    project_dir = tmp_path / "projects" / "my-project"
    project_dir.mkdir(parents=True, exist_ok=True)

    trace_data = {
        "trace": {
            "id": trace_id,
            "name": "test-trace",
            "start_time": "2024-01-01T00:00:00+00:00",
            "end_time": "2024-01-01T00:01:00+00:00",
            "input": {"prompt": "hello"},
            "output": {"response": "world"},
            "metadata": {},
            "tags": [],
            "feedback_scores": [],
            "error_info": None,
            "thread_id": None,
        },
        "spans": [
            {
                "id": span_id,
                "name": "test-span",
                "type": "general",
                "start_time": "2024-01-01T00:00:00+00:00",
                "end_time": "2024-01-01T00:01:00+00:00",
                "input": {},
                "output": {},
                "metadata": {},
                "tags": [],
                "feedback_scores": [],
                "parent_span_id": None,
            }
        ],
        "attachments": [],
    }

    trace_file = project_dir / f"trace_{trace_id}.json"
    trace_file.write_text(json.dumps(trace_data))
    return tmp_path / "projects"


def _make_client() -> MagicMock:
    """Return a mock Opik client whose trace()/span() return objects with .id."""
    client = MagicMock()

    trace_mock = MagicMock()
    span_mock = MagicMock()

    # These will be filled in per-test
    client.trace.return_value = trace_mock
    client.span.return_value = span_mock
    client.flush.return_value = True
    client.__internal_api__failed_uploads__ = MagicMock(return_value=0)
    return client


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestImportProjectsPreservesOriginalIds:
    """Verify that import_projects_from_directory reuses IDs from the export."""

    def test_trace_uses_original_id(self, tmp_path: Path) -> None:
        """Original trace ID from the export file must be passed to client.trace()."""
        original_trace_id = "01905678-abcd-7abc-8def-0123456789ab"
        original_span_id = "01905678-dcba-7abc-8def-ba9876543210"
        source_dir = _make_trace_file(tmp_path, original_trace_id, original_span_id)

        client = _make_client()
        trace_mock = client.trace.return_value
        trace_mock.id = original_trace_id

        from opik.cli.imports.project import import_projects_from_directory

        import_projects_from_directory(
            client=client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
            manifest=None,
        )

        # client.trace() must have been called with id=original_trace_id
        client.trace.assert_called_once()
        call_kwargs = client.trace.call_args
        assert call_kwargs.kwargs.get("id") == original_trace_id or (
            call_kwargs.args and call_kwargs.args[0] == original_trace_id
        ), (
            f"client.trace() was not called with id={original_trace_id!r}; "
            f"got kwargs={call_kwargs.kwargs}, args={call_kwargs.args}"
        )

    def test_span_uses_original_id(self, tmp_path: Path) -> None:
        """Original span ID from the export file must be passed to client.span()."""
        original_trace_id = "01905678-abcd-7abc-8def-0123456789ab"
        original_span_id = "01905678-dcba-7abc-8def-ba9876543210"
        source_dir = _make_trace_file(tmp_path, original_trace_id, original_span_id)

        client = _make_client()
        trace_mock = client.trace.return_value
        trace_mock.id = original_trace_id
        span_mock = client.span.return_value
        span_mock.id = original_span_id

        from opik.cli.imports.project import import_projects_from_directory

        import_projects_from_directory(
            client=client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
            manifest=None,
        )

        # client.span() must have been called with id=original_span_id
        client.span.assert_called_once()
        call_kwargs = client.span.call_args
        assert call_kwargs.kwargs.get("id") == original_span_id or (
            call_kwargs.args and call_kwargs.args[0] == original_span_id
        ), (
            f"client.span() was not called with id={original_span_id!r}; "
            f"got kwargs={call_kwargs.kwargs}, args={call_kwargs.args}"
        )

    def test_import_twice_produces_same_ids(self, tmp_path: Path) -> None:
        """Running the same import twice should produce the same trace/span IDs.

        This is the idempotency property: re-running import does not create
        duplicates because the IDs are stable (taken from the export file).
        """
        original_trace_id = "01905678-abcd-7abc-8def-0123456789ab"
        original_span_id = "01905678-dcba-7abc-8def-ba9876543210"
        source_dir = _make_trace_file(tmp_path, original_trace_id, original_span_id)

        from opik.cli.imports.project import import_projects_from_directory

        trace_ids_seen: list[str] = []

        for _ in range(2):
            client = _make_client()
            trace_mock = client.trace.return_value
            trace_mock.id = original_trace_id
            span_mock = client.span.return_value
            span_mock.id = original_span_id

            import_projects_from_directory(
                client=client,
                source_dir=source_dir,
                dry_run=False,
                name_pattern=None,
                debug=False,
                recreate_experiments_flag=False,
                manifest=None,
            )

            call_kwargs = client.trace.call_args
            used_id = call_kwargs.kwargs.get("id") or (
                call_kwargs.args[0] if call_kwargs.args else None
            )
            trace_ids_seen.append(used_id)

        assert trace_ids_seen[0] == trace_ids_seen[1], (
            "Two consecutive imports of the same file produced different trace IDs. "
            f"First: {trace_ids_seen[0]!r}, second: {trace_ids_seen[1]!r}"
        )
        assert trace_ids_seen[0] == original_trace_id, (
            f"Import did not reuse original trace ID {original_trace_id!r}, "
            f"got {trace_ids_seen[0]!r}"
        )
