"""Attachment scenarios — explicit and implicit."""

from typing import List, Set

import opik
from opik import Attachment

from . import _helpers
from ._helpers import KB, Metrics


def test_traces_with_explicit_attachments(
    metrics: Metrics, load_scale: float
) -> None:
    """Traces with explicit ``Attachment`` uploads, via ``@opik.track``.

    Inside a ``@opik.track``-decorated handler, two 50 KB binary
    attachments are added with ``opik.update_current_trace(attachments=...)``
    — the public pattern for attaching arbitrary files to the active
    trace from inside instrumented user code. Stresses the multipart
    upload path and the ``flush_tracker()`` contract around in-flight
    uploads.

    Volume: 500 traces × 2 attachments × 50 KB ≈ 50 MB of attachment
    payload total, plus 1k multipart uploads to coordinate.

    Verifies every submitted trace id lands with required fields set, and
    that the attachment-list endpoint reports both attachments on a
    sampled trace.
    """
    trace_count: int = int(500 * load_scale)
    attachments_per_trace: int = 2
    attachment_bytes: int = 50 * KB
    trace_input_bytes: int = 100
    project_name: str = _helpers.unique_project_name("explicit-attachments")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["attachments_per_trace"] = attachments_per_trace
    metrics["attachment_bytes"] = attachment_bytes
    metrics["trace_input_bytes"] = trace_input_bytes

    submitted_trace_ids: List[str] = []

    @opik.track(project_name=project_name)
    def handle_request(prompt: str) -> str:
        opik.update_current_trace(
            attachments=[
                Attachment(
                    data=_helpers.random_bytes(attachment_bytes),
                    file_name=f"attachment-{j}.bin",
                    content_type="application/octet-stream",
                )
                for j in range(attachments_per_trace)
            ]
        )
        submitted_trace_ids.append(opik.opik_context.get_current_trace_data().id)
        return f"echo: {prompt}"

    with metrics.timer("logging"):
        for _ in range(trace_count):
            handle_request(prompt=_helpers.random_text(trace_input_bytes))
            _helpers.think_time()

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    last_trace_id: str = submitted_trace_ids[-1]
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client, project_name=project_name, expected_ids=set(submitted_trace_ids)
        )
        delivered_attachment_count: int = _helpers.verify_attachments(
            client,
            project_name=project_name,
            entity_type="trace",
            entity_id=last_trace_id,
            expected_count=attachments_per_trace,
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)
    metrics["delivered_attachments_on_sample_trace"] = delivered_attachment_count
    assert delivered_attachment_count >= attachments_per_trace


def test_traces_with_implicit_attachments(
    metrics: Metrics, load_scale: float
) -> None:
    """Traces whose attachments are extracted from base64 input automatically.

    The handler accepts an ``image`` argument whose value is a
    ``data:image/png;base64,<~400 KB>`` URL. Because the embedded base64
    blob exceeds ``min_base64_embedded_attachment_size`` (250 KB by
    default), the SDK's attachment-extraction pipeline detects it and
    uploads it as an attachment without any explicit user action. This
    is the path most user code hits when logging multi-modal LLM I/O.

    Volume: 500 traces × 400 KB of base64 ≈ 200 MB of payload that the
    SDK has to scan, extract, and upload as 500 attachments.

    Verifies every submitted trace id lands with required fields set, and
    that at least one extracted attachment is reported on a sampled trace.
    """
    trace_count: int = int(500 * load_scale)
    embedded_base64_bytes: int = 400 * KB
    trace_prompt_bytes: int = 100
    project_name: str = _helpers.unique_project_name("implicit-attachments")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["embedded_base64_bytes"] = embedded_base64_bytes
    metrics["trace_prompt_bytes"] = trace_prompt_bytes

    submitted_trace_ids: List[str] = []

    @opik.track(project_name=project_name)
    def handle_image_request(prompt: str, image: str) -> str:
        submitted_trace_ids.append(opik.opik_context.get_current_trace_data().id)
        return f"caption for {prompt}: {image[:32]}..."

    with metrics.timer("logging"):
        for _ in range(trace_count):
            large_base64: str = _helpers.random_base64_png(embedded_base64_bytes)
            handle_image_request(
                prompt=_helpers.random_text(trace_prompt_bytes),
                image=f"data:image/png;base64,{large_base64}",
            )
            _helpers.think_time()

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    last_trace_id: str = submitted_trace_ids[-1]
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client, project_name=project_name, expected_ids=set(submitted_trace_ids)
        )
        delivered_attachment_count: int = _helpers.verify_attachments(
            client,
            project_name=project_name,
            entity_type="trace",
            entity_id=last_trace_id,
            expected_count=1,
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)
    metrics["delivered_attachments_on_sample_trace"] = delivered_attachment_count
    assert delivered_attachment_count >= 1
