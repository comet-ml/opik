"""Tests for attachment support in CLI export and import."""

import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch


from opik.cli.exports.project import (
    _download_attachment_file,
    _fetch_attachments,
    export_traces,
)
from opik.cli.imports.project import (
    _upload_attachments_for_trace,
    import_projects_from_directory,
)


# ─────────────────────────────────────────────────────────────────────────────
# Helpers shared across test classes
# ─────────────────────────────────────────────────────────────────────────────


def _rest_attachment(file_name, mime_type="image/png", file_size=1024):
    """Simulate a REST API Attachment response object."""
    return SimpleNamespace(
        file_name=file_name,
        mime_type=mime_type,
        file_size=file_size,
        link="http://storage.example.com/presigned-url",
    )


def _make_attachment_client(trace_attachments=None, span_attachments=None):
    """Build a mock AttachmentClient with configurable attachment lists.

    Args:
        trace_attachments: list of dicts with keys accepted by _rest_attachment()
        span_attachments: mapping of {span_id: [dicts]} for span-level attachments
    """
    client = MagicMock()

    def _get_list(project_name, entity_id, entity_type):
        if entity_type == "trace":
            return [_rest_attachment(**a) for a in (trace_attachments or [])]
        elif entity_type == "span":
            span_atts = (span_attachments or {}).get(entity_id, [])
            return [_rest_attachment(**a) for a in span_atts]
        return []

    client.get_attachment_list.side_effect = _get_list
    client.download_attachment.return_value = iter([b"binary content"])
    return client


def _make_trace(trace_id, name="test-trace"):
    """Simulate a trace object as returned by the REST API."""
    t = MagicMock()
    t.id = trace_id
    t.name = name
    t.model_dump.return_value = {
        "id": trace_id,
        "name": name,
        "start_time": "2026-01-01T00:00:00Z",
        "end_time": "2026-01-01T00:00:01Z",
        "input": {},
        "output": {},
        "metadata": None,
        "tags": None,
        "feedback_scores": None,
        "error_info": None,
        "usage": None,
    }
    return t


def _make_span(span_id, trace_id, parent_span_id=None):
    """Simulate a span object."""
    s = MagicMock()
    s.id = span_id
    s.model_dump.return_value = {
        "id": span_id,
        "trace_id": trace_id,
        "parent_span_id": parent_span_id,
        "name": f"span-{span_id}",
        "type": "general",
        "start_time": "2026-01-01T00:00:00Z",
        "end_time": "2026-01-01T00:00:01Z",
        "input": {},
        "output": {},
        "metadata": None,
        "tags": None,
        "feedback_scores": None,
        "usage": None,
    }
    return s


def _make_opik_client(traces, spans_by_trace_id, attachment_client=None):
    """Build a mock opik.Opik client for export tests.

    Args:
        traces: list of trace mock objects
        spans_by_trace_id: mapping of {trace_id: [span_mock]}
        attachment_client: optional mock AttachmentClient
    """
    client = MagicMock()

    # Paginated traces response — first call returns traces, second returns empty
    page1 = MagicMock()
    page1.content = traces
    page1.total = len(traces)

    page2 = MagicMock()
    page2.content = []
    page2.total = len(traces)

    client.rest_client.traces.get_traces_by_project.side_effect = [page1, page2]

    # Bulk span fetch (Phase 2 in 3-phase export): collect all spans and set trace_id
    all_spans = []
    for tid, spans in spans_by_trace_id.items():
        for span in spans:
            span.trace_id = tid
            all_spans.append(span)

    span_page1 = MagicMock()
    span_page1.content = all_spans
    span_page2 = MagicMock()
    span_page2.content = []

    client.rest_client.spans.get_spans_by_project.side_effect = [span_page1, span_page2]

    client.get_attachment_client.return_value = attachment_client or MagicMock()
    return client


def _write_trace_file(project_dir: Path, trace_data: dict) -> Path:
    """Write a trace JSON file and return its path."""
    project_dir.mkdir(parents=True, exist_ok=True)
    trace_id = trace_data["trace"]["id"]
    path = project_dir / f"trace_{trace_id}.json"
    path.write_text(json.dumps(trace_data))
    return path


def _write_attachment_file(
    project_dir: Path,
    entity_type: str,
    entity_id: str,
    file_name: str,
    content: bytes = b"file data",
) -> Path:
    """Write a binary attachment file and return its path."""
    path = project_dir / "attachments" / entity_type / entity_id / file_name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


# ─────────────────────────────────────────────────────────────────────────────
# Tests: _fetch_attachments()
# ─────────────────────────────────────────────────────────────────────────────


class TestFetchAttachments:
    def test_fetch_attachments__trace_entity__returns_metadata(self):
        att_client = _make_attachment_client(
            trace_attachments=[
                {"file_name": "img.png", "mime_type": "image/png", "file_size": 500}
            ]
        )
        result = _fetch_attachments(att_client, "proj", "trace-1", [])

        assert len(result) == 1
        assert result[0] == {
            "entity_type": "trace",
            "entity_id": "trace-1",
            "file_name": "img.png",
            "mime_type": "image/png",
            "file_size": 500,
        }

    def test_fetch_attachments__span_entity__returns_metadata(self):
        att_client = _make_attachment_client(
            span_attachments={
                "span-1": [
                    {"file_name": "data.csv", "mime_type": "text/csv", "file_size": 200}
                ]
            }
        )
        result = _fetch_attachments(att_client, "proj", "trace-1", ["span-1"])

        assert len(result) == 1
        assert result[0] == {
            "entity_type": "span",
            "entity_id": "span-1",
            "file_name": "data.csv",
            "mime_type": "text/csv",
            "file_size": 200,
        }

    def test_fetch_attachments__trace_and_span__returns_both(self):
        att_client = _make_attachment_client(
            trace_attachments=[{"file_name": "trace.png"}],
            span_attachments={
                "span-1": [{"file_name": "span.txt", "mime_type": "text/plain"}]
            },
        )
        result = _fetch_attachments(att_client, "proj", "trace-1", ["span-1"])

        assert len(result) == 2
        types = {a["entity_type"] for a in result}
        assert types == {"trace", "span"}

    def test_fetch_attachments__no_attachments__returns_empty_list(self):
        att_client = _make_attachment_client()
        result = _fetch_attachments(att_client, "proj", "trace-1", ["span-1"])
        assert result == []

    def test_fetch_attachments__multiple_spans__collects_all(self):
        att_client = _make_attachment_client(
            span_attachments={
                "span-a": [{"file_name": "a.png"}],
                "span-b": [{"file_name": "b.png"}, {"file_name": "c.png"}],
            }
        )
        result = _fetch_attachments(att_client, "proj", "trace-1", ["span-a", "span-b"])

        assert len(result) == 3
        file_names = {a["file_name"] for a in result}
        assert file_names == {"a.png", "b.png", "c.png"}

    def test_fetch_attachments__conflict_409__skips_entity(self):
        from opik.rest_api.core.api_error import ApiError

        att_client = MagicMock()
        att_client.get_attachment_list.side_effect = ApiError(
            status_code=409, body="conflict"
        )

        # 409 is silently skipped; the function returns what it could collect
        result = _fetch_attachments(att_client, "proj", "trace-1", ["span-1"])
        assert result == []

    def test_fetch_attachments__unexpected_api_error__raises(self):
        from opik.rest_api.core.api_error import ApiError

        att_client = MagicMock()
        att_client.get_attachment_list.side_effect = ApiError(
            status_code=503, body="unavailable"
        )

        import pytest

        with pytest.raises(ApiError):
            _fetch_attachments(att_client, "proj", "trace-1", [])

    def test_fetch_attachments__trace_and_spans__calls_get_list_for_each(self):
        att_client = _make_attachment_client()
        _fetch_attachments(att_client, "proj", "trace-1", ["span-a", "span-b"])

        calls = att_client.get_attachment_list.call_args_list
        # One call for the trace, one per span
        assert len(calls) == 3
        entity_types = [c[0][2] for c in calls]  # 3rd positional arg
        assert entity_types.count("trace") == 1
        assert entity_types.count("span") == 2


# ─────────────────────────────────────────────────────────────────────────────
# Tests: _download_attachment_file()
# ─────────────────────────────────────────────────────────────────────────────


class TestDownloadAttachmentFile:
    _ATT = {
        "entity_type": "trace",
        "entity_id": "trace-123",
        "file_name": "img.png",
        "mime_type": "image/png",
        "file_size": 12,
    }

    def test_download_attachment_file__single_chunk__writes_bytes_to_correct_path(
        self, tmp_path
    ):
        att_client = MagicMock()
        att_client.download_attachment.return_value = iter([b"hello world"])

        result = _download_attachment_file(
            att_client, "proj", self._ATT, tmp_path, force=False
        )

        assert result is True
        dest = tmp_path / "attachments" / "trace" / "trace-123" / "img.png"
        assert dest.exists()
        assert dest.read_bytes() == b"hello world"

    def test_download_attachment_file__multiple_chunks__concatenates_bytes(
        self, tmp_path
    ):
        att_client = MagicMock()
        att_client.download_attachment.return_value = iter(
            [b"chunk1", b"chunk2", b"chunk3"]
        )

        _download_attachment_file(att_client, "proj", self._ATT, tmp_path, force=False)

        dest = tmp_path / "attachments" / "trace" / "trace-123" / "img.png"
        assert dest.read_bytes() == b"chunk1chunk2chunk3"

    def test_download_attachment_file__existing_file_no_force__skips_download(
        self, tmp_path
    ):
        att_client = MagicMock()
        dest = tmp_path / "attachments" / "trace" / "trace-123" / "img.png"
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b"original")

        result = _download_attachment_file(
            att_client, "proj", self._ATT, tmp_path, force=False
        )

        assert result is True
        att_client.download_attachment.assert_not_called()
        assert dest.read_bytes() == b"original"  # unchanged

    def test_download_attachment_file__existing_file_with_force__overwrites(
        self, tmp_path
    ):
        att_client = MagicMock()
        att_client.download_attachment.return_value = iter([b"new content"])
        dest = tmp_path / "attachments" / "trace" / "trace-123" / "img.png"
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b"old content")

        result = _download_attachment_file(
            att_client, "proj", self._ATT, tmp_path, force=True
        )

        assert result is True
        att_client.download_attachment.assert_called_once()
        assert dest.read_bytes() == b"new content"

    def test_download_attachment_file__os_error__returns_false(self, tmp_path):
        att_client = MagicMock()
        att_client.download_attachment.side_effect = OSError("S3 unavailable")

        result = _download_attachment_file(
            att_client, "proj", self._ATT, tmp_path, force=False
        )

        assert result is False

    def test_download_attachment_file__nested_entity_id__creates_parent_directories(
        self, tmp_path
    ):
        att_client = MagicMock()
        att_client.download_attachment.return_value = iter([b"data"])
        att = dict(self._ATT, entity_id="a/b/c", file_name="deep.txt")

        _download_attachment_file(att_client, "proj", att, tmp_path, force=False)

        dest = tmp_path / "attachments" / "trace" / "a/b/c" / "deep.txt"
        assert dest.exists()

    def test_download_attachment_file__span_entity__saved_under_span_directory(
        self, tmp_path
    ):
        att_client = MagicMock()
        att_client.download_attachment.return_value = iter([b"span bytes"])
        att = {
            "entity_type": "span",
            "entity_id": "span-xyz",
            "file_name": "output.txt",
            "mime_type": "text/plain",
            "file_size": 10,
        }

        _download_attachment_file(att_client, "proj", att, tmp_path, force=False)

        dest = tmp_path / "attachments" / "span" / "span-xyz" / "output.txt"
        assert dest.exists()
        assert dest.read_bytes() == b"span bytes"


# ─────────────────────────────────────────────────────────────────────────────
# Tests: _upload_attachments_for_trace()
# ─────────────────────────────────────────────────────────────────────────────


class TestUploadAttachmentsForTrace:
    """Unit tests for the _upload_attachments_for_trace helper.

    This private helper is tested directly (rather than solely via
    import_projects_from_directory) because it contains security-critical path
    validation logic (traversal prevention) and ID-translation rules that are
    difficult to observe through the public API without significant fixture
    overhead.  The integration-level behaviour (attachment uploads wired into
    the full import flow) is covered by TestImportProjectsUploadsAttachments.
    """

    def test_uploads_trace_attachment__with_new_trace_id__happyflow(self, tmp_path):
        client = MagicMock()
        project_dir = tmp_path / "my-project"
        _write_attachment_file(
            project_dir, "trace", "old-trace", "img.png", b"image data"
        )

        attachments = [
            {
                "entity_type": "trace",
                "entity_id": "old-trace",
                "file_name": "img.png",
                "mime_type": "image/png",
            }
        ]
        _upload_attachments_for_trace(
            client=client,
            project_dir=project_dir,
            attachments=attachments,
            new_trace_id="new-trace",
            span_id_map={},
            project_name="proj",
        )

        client.queue_attachment_upload.assert_called_once_with(
            entity_type="trace",
            entity_id="new-trace",
            project_name="proj",
            file_path=str(
                project_dir / "attachments" / "trace" / "old-trace" / "img.png"
            ),
            file_name="img.png",
            mime_type="image/png",
        )

    def test_uploads_span_attachment__with_translated_span_id__happyflow(
        self, tmp_path
    ):
        client = MagicMock()
        project_dir = tmp_path / "my-project"
        _write_attachment_file(project_dir, "span", "old-span", "data.csv", b"csv data")

        attachments = [
            {
                "entity_type": "span",
                "entity_id": "old-span",
                "file_name": "data.csv",
                "mime_type": "text/csv",
            }
        ]
        _upload_attachments_for_trace(
            client=client,
            project_dir=project_dir,
            attachments=attachments,
            new_trace_id="new-trace",
            span_id_map={"old-span": "new-span"},
            project_name="proj",
        )

        client.queue_attachment_upload.assert_called_once_with(
            entity_type="span",
            entity_id="new-span",
            project_name="proj",
            file_path=str(
                project_dir / "attachments" / "span" / "old-span" / "data.csv"
            ),
            file_name="data.csv",
            mime_type="text/csv",
        )

    def test_uploads_attachments__trace_and_span__both_uploaded(self, tmp_path):
        client = MagicMock()
        project_dir = tmp_path / "my-project"
        _write_attachment_file(project_dir, "trace", "old-trace", "img.png")
        _write_attachment_file(project_dir, "span", "old-span", "data.csv")

        attachments = [
            {
                "entity_type": "trace",
                "entity_id": "old-trace",
                "file_name": "img.png",
                "mime_type": "image/png",
            },
            {
                "entity_type": "span",
                "entity_id": "old-span",
                "file_name": "data.csv",
                "mime_type": "text/csv",
            },
        ]
        _upload_attachments_for_trace(
            client=client,
            project_dir=project_dir,
            attachments=attachments,
            new_trace_id="new-trace",
            span_id_map={"old-span": "new-span"},
            project_name="proj",
        )

        assert client.queue_attachment_upload.call_count == 2

    def test_upload_attachment__span_not_in_id_map__skips(self, tmp_path):
        client = MagicMock()
        project_dir = tmp_path / "my-project"
        _write_attachment_file(project_dir, "span", "unknown-span", "file.txt")

        attachments = [
            {
                "entity_type": "span",
                "entity_id": "unknown-span",
                "file_name": "file.txt",
                "mime_type": "text/plain",
            }
        ]
        _upload_attachments_for_trace(
            client=client,
            project_dir=project_dir,
            attachments=attachments,
            new_trace_id="new-trace",
            span_id_map={},  # unknown-span not in map
            project_name="proj",
        )

        client.queue_attachment_upload.assert_not_called()

    def test_upload_attachment__file_missing_on_disk__skips(self, tmp_path):
        client = MagicMock()
        project_dir = tmp_path / "my-project"
        # No file written on disk

        attachments = [
            {
                "entity_type": "trace",
                "entity_id": "old-trace",
                "file_name": "missing.png",
                "mime_type": "image/png",
            }
        ]
        _upload_attachments_for_trace(
            client=client,
            project_dir=project_dir,
            attachments=attachments,
            new_trace_id="new-trace",
            span_id_map={},
            project_name="proj",
        )

        client.queue_attachment_upload.assert_not_called()

    def test_upload_attachment__missing_required_keys__skips(self, tmp_path):
        client = MagicMock()
        project_dir = tmp_path / "my-project"

        attachments = [
            {"entity_type": "trace"},  # missing entity_id and file_name
        ]
        _upload_attachments_for_trace(
            client=client,
            project_dir=project_dir,
            attachments=attachments,
            new_trace_id="new-trace",
            span_id_map={},
            project_name="proj",
        )

        client.queue_attachment_upload.assert_not_called()


# ─────────────────────────────────────────────────────────────────────────────
# Integration tests: export_traces() writes attachments to JSON and disk
# ─────────────────────────────────────────────────────────────────────────────


TRACE_ID = "00000000-0000-7000-0000-000000000001"
SPAN_ID = "00000000-0000-7000-0000-000000000002"


class TestExportTracesWritesAttachmentMetadata:
    """Verify that export_traces() embeds attachment metadata in the JSON file."""

    def _run_export(self, client, project_dir, include_attachments=True, force=False):
        export_traces(
            client=client,
            project_name="test-project",
            project_dir=project_dir,
            max_results=None,
            filter_string=None,
            format="json",
            debug=False,
            force=force,
            show_progress=False,
            include_attachments=include_attachments,
        )

    def test_export_traces__with_attachment__key_present_in_json(self, tmp_path):
        trace = _make_trace(TRACE_ID)
        span = _make_span(SPAN_ID, TRACE_ID)
        att_client = _make_attachment_client(
            trace_attachments=[
                {"file_name": "img.png", "mime_type": "image/png", "file_size": 100}
            ]
        )
        client = _make_opik_client(
            traces=[trace],
            spans_by_trace_id={TRACE_ID: [span]},
            attachment_client=att_client,
        )

        self._run_export(client, tmp_path, include_attachments=True)

        trace_file = tmp_path / f"trace_{TRACE_ID}.json"
        assert trace_file.exists()
        data = json.loads(trace_file.read_text())
        assert "attachments" in data
        assert len(data["attachments"]) == 1
        att = data["attachments"][0]
        assert att["entity_type"] == "trace"
        assert att["entity_id"] == TRACE_ID
        assert att["file_name"] == "img.png"
        assert att["mime_type"] == "image/png"

    def test_export_traces__no_attachments__list_is_empty(self, tmp_path):
        trace = _make_trace(TRACE_ID)
        att_client = _make_attachment_client()  # no attachments
        client = _make_opik_client(
            traces=[trace],
            spans_by_trace_id={TRACE_ID: []},
            attachment_client=att_client,
        )

        self._run_export(client, tmp_path, include_attachments=True)

        data = json.loads((tmp_path / f"trace_{TRACE_ID}.json").read_text())
        assert data["attachments"] == []

    def test_export_traces__no_attachments_flag__omits_metadata(self, tmp_path):
        trace = _make_trace(TRACE_ID)
        att_client = _make_attachment_client(
            trace_attachments=[{"file_name": "img.png"}]
        )
        client = _make_opik_client(
            traces=[trace],
            spans_by_trace_id={TRACE_ID: []},
            attachment_client=att_client,
        )

        self._run_export(client, tmp_path, include_attachments=False)

        data = json.loads((tmp_path / f"trace_{TRACE_ID}.json").read_text())
        # attachments key present but empty (include_attachments=False → att_client is None)
        assert data["attachments"] == []
        # And get_attachment_client was never called
        client.get_attachment_client.assert_not_called()

    def test_export_traces__attachment_download__writes_to_correct_path(self, tmp_path):
        trace = _make_trace(TRACE_ID)
        att_client = _make_attachment_client(
            trace_attachments=[
                {"file_name": "photo.jpg", "mime_type": "image/jpeg", "file_size": 50}
            ]
        )
        att_client.download_attachment.return_value = iter([b"jpeg bytes"])
        client = _make_opik_client(
            traces=[trace],
            spans_by_trace_id={TRACE_ID: []},
            attachment_client=att_client,
        )

        self._run_export(client, tmp_path, include_attachments=True)

        dest = tmp_path / "attachments" / "trace" / TRACE_ID / "photo.jpg"
        assert dest.exists()
        assert dest.read_bytes() == b"jpeg bytes"

    def test_export_traces__span_attachment__binary_downloaded(self, tmp_path):
        trace = _make_trace(TRACE_ID)
        span = _make_span(SPAN_ID, TRACE_ID)
        att_client = _make_attachment_client(
            span_attachments={
                SPAN_ID: [
                    {
                        "file_name": "span_output.txt",
                        "mime_type": "text/plain",
                        "file_size": 10,
                    }
                ]
            }
        )
        att_client.download_attachment.return_value = iter([b"span text"])
        client = _make_opik_client(
            traces=[trace],
            spans_by_trace_id={TRACE_ID: [span]},
            attachment_client=att_client,
        )

        self._run_export(client, tmp_path, include_attachments=True)

        dest = tmp_path / "attachments" / "span" / SPAN_ID / "span_output.txt"
        assert dest.exists()
        assert dest.read_bytes() == b"span text"

    def test_export_traces__existing_attachment_file_no_force__skips_download(
        self, tmp_path
    ):
        trace = _make_trace(TRACE_ID)
        att_client = _make_attachment_client(
            trace_attachments=[{"file_name": "img.png"}]
        )
        # Pre-create the attachment file
        dest = tmp_path / "attachments" / "trace" / TRACE_ID / "img.png"
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b"already here")

        client = _make_opik_client(
            traces=[trace],
            spans_by_trace_id={TRACE_ID: []},
            attachment_client=att_client,
        )

        self._run_export(client, tmp_path, include_attachments=True, force=False)

        # File unchanged; download_attachment was never called
        att_client.download_attachment.assert_not_called()
        assert dest.read_bytes() == b"already here"


# ─────────────────────────────────────────────────────────────────────────────
# Integration tests: import_projects_from_directory() uploads attachments
# ─────────────────────────────────────────────────────────────────────────────

_TRACE_WITH_ATTACHMENTS = {
    "trace": {
        "id": "orig-trace-id",
        "name": "test-trace",
        "start_time": "2026-01-01T00:00:00Z",
        "end_time": "2026-01-01T00:00:01Z",
        "input": {},
        "output": {},
        "metadata": None,
        "tags": None,
        "feedback_scores": None,
        "error_info": None,
        "thread_id": None,
    },
    "spans": [
        {
            "id": "orig-span-id",
            "trace_id": "orig-trace-id",
            "parent_span_id": None,
            "name": "child-span",
            "type": "general",
            "start_time": "2026-01-01T00:00:00Z",
            "end_time": "2026-01-01T00:00:00.500000Z",
            "input": {},
            "output": {},
            "metadata": None,
            "tags": None,
            "feedback_scores": None,
            "usage": None,
        }
    ],
    "attachments": [
        {
            "entity_type": "trace",
            "entity_id": "orig-trace-id",
            "file_name": "trace_img.png",
            "mime_type": "image/png",
            "file_size": 100,
        },
        {
            "entity_type": "span",
            "entity_id": "orig-span-id",
            "file_name": "span_data.csv",
            "mime_type": "text/csv",
            "file_size": 50,
        },
    ],
    "downloaded_at": "2026-01-01T00:01:00",
    "project_name": "test-project",
}


def _make_import_client(new_trace_id="new-trace-id", new_span_id="new-span-id"):
    """Build a mock opik.Opik client for import tests."""
    client = MagicMock()
    mock_trace = MagicMock()
    mock_trace.id = new_trace_id
    client.trace.return_value = mock_trace

    mock_span = MagicMock()
    mock_span.id = new_span_id
    client.span.return_value = mock_span

    # flush() is called once per project after all traces are processed.
    client.flush.return_value = True
    return client


class TestImportProjectsUploadsAttachments:
    """Integration tests verifying attachment uploads during project import."""

    def test_import_projects__trace_attachment__uploaded_with_new_trace_id(
        self, tmp_path
    ):
        client = _make_import_client(new_trace_id="new-trace", new_span_id="new-span")
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, _TRACE_WITH_ATTACHMENTS)
        _write_attachment_file(
            project_dir, "trace", "orig-trace-id", "trace_img.png", b"img bytes"
        )
        _write_attachment_file(
            project_dir, "span", "orig-span-id", "span_data.csv", b"csv bytes"
        )

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=False,
            name_pattern=None,
            debug=False,
            include_attachments=True,
        )

        upload_calls = client.queue_attachment_upload.call_args_list

        # Verify trace attachment uses new trace ID
        trace_call = next(
            c for c in upload_calls if c.kwargs.get("entity_type") == "trace"
        )
        assert trace_call.kwargs["entity_id"] == "new-trace"
        assert trace_call.kwargs["file_name"] == "trace_img.png"
        assert trace_call.kwargs["mime_type"] == "image/png"

    def test_import_projects__span_attachment__uploaded_with_new_span_id(
        self, tmp_path
    ):
        client = _make_import_client(new_trace_id="new-trace", new_span_id="new-span")
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, _TRACE_WITH_ATTACHMENTS)
        _write_attachment_file(project_dir, "trace", "orig-trace-id", "trace_img.png")
        _write_attachment_file(project_dir, "span", "orig-span-id", "span_data.csv")

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=False,
            name_pattern=None,
            debug=False,
            include_attachments=True,
        )

        upload_calls = client.queue_attachment_upload.call_args_list

        span_call = next(
            c for c in upload_calls if c.kwargs.get("entity_type") == "span"
        )
        assert span_call.kwargs["entity_id"] == "new-span"
        assert span_call.kwargs["file_name"] == "span_data.csv"

    def test_import_projects__multiple_attachments__all_uploaded(self, tmp_path):
        client = _make_import_client()
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, _TRACE_WITH_ATTACHMENTS)
        _write_attachment_file(project_dir, "trace", "orig-trace-id", "trace_img.png")
        _write_attachment_file(project_dir, "span", "orig-span-id", "span_data.csv")

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=False,
            name_pattern=None,
            debug=False,
            include_attachments=True,
        )

        assert client.queue_attachment_upload.call_count == 2

    def test_import_projects__no_attachments_flag__skips_all_uploads(self, tmp_path):
        client = _make_import_client()
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, _TRACE_WITH_ATTACHMENTS)
        _write_attachment_file(project_dir, "trace", "orig-trace-id", "trace_img.png")

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=False,
            name_pattern=None,
            debug=False,
            include_attachments=False,
        )

        # queue_attachment_upload must not be called at all
        client.queue_attachment_upload.assert_not_called()

    def test_import_projects__attachment_files_missing__trace_still_imported(
        self, tmp_path
    ):
        """Missing attachment files emit a warning but don't abort the trace import."""
        client = _make_import_client()
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, _TRACE_WITH_ATTACHMENTS)
        # Do NOT write attachment files — simulate export with --no-attachments

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=False,
            name_pattern=None,
            debug=False,
            include_attachments=True,
        )

        # Trace and span must still be created
        assert client.trace.call_count == 1
        assert client.span.call_count == 1
        # No uploads because files don't exist
        client.queue_attachment_upload.assert_not_called()

    def test_import_projects__dry_run__skips_uploads(self, tmp_path):
        client = _make_import_client()
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, _TRACE_WITH_ATTACHMENTS)
        _write_attachment_file(project_dir, "trace", "orig-trace-id", "trace_img.png")

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=True,
            name_pattern=None,
            debug=False,
            include_attachments=True,
        )

        client.queue_attachment_upload.assert_not_called()

    def test_import_projects__no_attachments_key__imports_normally(self, tmp_path):
        """Trace files from before attachment support (no 'attachments' key) import fine."""
        trace_data = {
            "trace": {
                "id": "legacy-trace",
                "name": "legacy",
                "start_time": "2026-01-01T00:00:00Z",
                "end_time": "2026-01-01T00:00:01Z",
                "input": {},
                "output": {},
                "metadata": None,
                "tags": None,
                "feedback_scores": None,
                "error_info": None,
                "thread_id": None,
            },
            "spans": [],
            "downloaded_at": "2026-01-01T00:01:00",
            "project_name": "test-project",
            # NOTE: no "attachments" key
        }
        client = _make_import_client()
        project_dir = tmp_path / "test-project"
        _write_trace_file(project_dir, trace_data)

        import_projects_from_directory(
            client=client,
            source_dir=tmp_path,
            dry_run=False,
            name_pattern=None,
            debug=False,
            include_attachments=True,
        )

        assert client.trace.call_count == 1
        att_client = client.get_attachment_client.return_value
        att_client.upload_attachment.assert_not_called()


# ─────────────────────────────────────────────────────────────────────────────
# CLI flag tests: --no-attachments is accepted and propagates correctly
# ─────────────────────────────────────────────────────────────────────────────


class TestNoAttachmentsCliFlag:
    """Verify --no-attachments is wired through the CLI commands."""

    def test_export_project__no_attachments_flag__accepted(self):
        from click.testing import CliRunner
        from opik.cli.exports.project import export_project_command

        runner = CliRunner()
        with patch(
            "opik.cli.exports.project.export_project_by_name_or_id"
        ) as mock_export:
            result = runner.invoke(
                export_project_command,
                ["my-project", "--no-attachments"],
                obj={"workspace": "ws", "api_key": None},
                catch_exceptions=False,
            )

        assert result.exit_code == 0, result.output
        _, kwargs = mock_export.call_args
        assert kwargs.get("include_attachments") is False

    def test_export_project__default__includes_attachments(self):
        from click.testing import CliRunner
        from opik.cli.exports.project import export_project_command

        runner = CliRunner()
        with patch(
            "opik.cli.exports.project.export_project_by_name_or_id"
        ) as mock_export:
            result = runner.invoke(
                export_project_command,
                ["my-project"],
                obj={"workspace": "ws", "api_key": None},
                catch_exceptions=False,
            )

        assert result.exit_code == 0, result.output
        _, kwargs = mock_export.call_args
        assert kwargs.get("include_attachments") is True

    def test_import_project__no_attachments_flag__accepted(self):
        from click.testing import CliRunner
        from opik.cli.imports import import_group

        runner = CliRunner()
        # Patch _import_by_type where it is *defined* so the import command
        # calls the mock rather than the real function.
        with patch("opik.cli.imports._import_by_type") as mock_import:
            result = runner.invoke(
                import_group,
                ["my-workspace", "project", "my-project", "--no-attachments"],
                catch_exceptions=False,
            )

        assert result.exit_code == 0, result.output
        assert mock_import.called
        _, kwargs = mock_import.call_args
        assert kwargs.get("include_attachments") is False

    def test_import_project__default__includes_attachments(self):
        from click.testing import CliRunner
        from opik.cli.imports import import_group

        runner = CliRunner()
        with patch("opik.cli.imports._import_by_type") as mock_import:
            result = runner.invoke(
                import_group,
                ["my-workspace", "project", "my-project"],
                catch_exceptions=False,
            )

        assert result.exit_code == 0, result.output
        assert mock_import.called
        _, kwargs = mock_import.call_args
        assert kwargs.get("include_attachments") is True
