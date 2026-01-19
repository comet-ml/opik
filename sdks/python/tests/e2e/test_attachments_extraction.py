"""
E2E tests for automatic attachment extraction from trace/span input, output, and metadata.

This module tests the automatic extraction and upload of base64-encoded attachments
embedded in trace and span data when attachment extraction is enabled.
"""

import base64
import time

import pytest

import opik
from opik import id_helpers, datetime_helpers

from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME
from ..unit.api_objects.attachment import constants
from .. import testlib


@pytest.fixture(autouse=True)
def disable_tests_if_attachment_extraction_not_enabled(opik_client: opik.Opik):
    """Disable tests if attachment extraction is not enabled."""
    if not opik_client.config.is_attachment_extraction_active:
        pytest.skip("Attachment extraction is not enabled - skipping E2E tests")


@pytest.fixture(autouse=True, scope="module")
def configure_min_size_tests_env():
    with testlib.patch_environ({"OPIK_MIN_BASE64_EMBEDDED_ATTACHMENT_SIZE": "30"}):
        yield


def _create_base64_url(media_type: str, base64_data: str) -> str:
    """Create a data URL with base64-encoded content."""
    return f"data:{media_type};base64,{base64_data}"


def test_extraction__trace_with_end_time__extracts_attachments_from_input(
    opik_client: opik.Opik,
):
    """Test that traces with end_time has attachments extracted from the input field."""
    trace_id = id_helpers.generate_id()

    # Create a trace with end_time and base64-encoded images in input
    opik_client.trace(
        id=trace_id,
        name="test-trace-extraction-input",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={
            "image1": _create_base64_url("image/png", constants.PNG_BASE64),
            "image2": _create_base64_url("image/jpeg", constants.JPEG_BASE64),
            "text": "regular text field",
        },
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
        len(base64.b64decode(constants.JPEG_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__trace_without_end_time__does_not_extract_attachments(
    opik_client: opik.Opik,
):
    """Test that traces without end_time does NOT have attachments extracted."""
    trace_id = id_helpers.generate_id()

    # Create a trace WITHOUT calling end() - no end_time set
    opik_client.trace(
        id=trace_id,
        name="test-trace-no-extraction",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={
            "image": _create_base64_url("image/png", constants.PNG_BASE64),
        },
    )
    # Note: NOT calling trace.end()

    opik_client.flush()

    # Wait a bit to ensure processing has completed
    time.sleep(2)

    # Verify NO attachments were extracted
    attachments_client = opik_client.get_attachment_client()

    attachment_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=trace_id,
        entity_type="trace",
    )

    assert (
        len(attachment_list) == 0
    ), f"Expected no attachments, but found {len(attachment_list)}"


def test_extraction__trace_with_end_time__extracts_attachments_from_output(
    opik_client: opik.Opik,
):
    """Test that traces with end_time has attachments extracted from the output field."""
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-extraction-output",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={"prompt": "generate an image"},
        output={
            "result_image": _create_base64_url("image/png", constants.PNG_BASE64),
            "result_pdf": _create_base64_url("application/pdf", constants.PDF_BASE64),
        },
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
        len(base64.b64decode(constants.PDF_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__trace_with_end_time__extracts_attachments_from_metadata(
    opik_client: opik.Opik,
):
    """Test that traces with end_time has attachments extracted from the metadata field."""
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-extraction-metadata",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        metadata={
            "screenshot": _create_base64_url("image/png", constants.PNG_BASE64),
            "version": "1.0",
        },
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [len(base64.b64decode(constants.PNG_BASE64))]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__trace_with_end_time__extracts_from_all_fields(
    opik_client: opik.Opik,
):
    """Test extraction from input, output, and metadata simultaneously."""
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-extraction-all-fields",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={
            "input_img": _create_base64_url("image/png", constants.PNG_BASE64),
        },
        output={
            "output_img": _create_base64_url("image/jpeg", constants.JPEG_BASE64),
        },
        metadata={
            "meta_gif": _create_base64_url("image/gif", constants.GIF89_BASE64),
        },
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
        len(base64.b64decode(constants.JPEG_BASE64)),
        len(base64.b64decode(constants.GIF89_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__span_with_end_time__extracts_attachments(
    opik_client: opik.Opik,
):
    """Test that spans with end_time has attachments extracted."""
    trace_id = id_helpers.generate_id()
    span_id = id_helpers.generate_id()

    # Create trace first
    trace = opik_client.trace(
        id=trace_id,
        name="test-trace-for-span-extraction",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Create a span with end_time and attachments
    trace.span(
        id=span_id,
        name="test-span-extraction",
        input={
            "image": _create_base64_url("image/png", constants.PNG_BASE64),
        },
        output={
            "result": _create_base64_url("image/webp", constants.WEBP_BASE64),
        },
        metadata={
            "meta_gif": _create_base64_url("image/gif", constants.GIF89_BASE64),
        },
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
        len(base64.b64decode(constants.WEBP_BASE64)),
        len(base64.b64decode(constants.GIF89_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=span_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__span_without_end_time__does_not_extract_attachments(
    opik_client: opik.Opik,
):
    """Test that spans without end_time does NOT have attachments extracted."""
    trace_id = id_helpers.generate_id()
    span_id = id_helpers.generate_id()

    # Create trace first
    trace = opik_client.trace(
        id=trace_id,
        name="test-trace-for-span-no-extraction",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Create a span WITHOUT an end_time set
    trace.span(
        id=span_id,
        name="test-span-no-extraction",
        input={
            "image": _create_base64_url("image/png", constants.PNG_BASE64),
        },
    )

    opik_client.flush()

    # Wait a bit to ensure processing has completed
    time.sleep(2)

    # Verify NO attachments were extracted
    attachments_client = opik_client.get_attachment_client()

    attachment_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=span_id,
        entity_type="span",
    )

    assert (
        len(attachment_list) == 0
    ), f"Expected no attachments, but found {len(attachment_list)}"


def test_extraction__trace_update__extracts_attachments(
    opik_client: opik.Opik,
):
    """Test that trace updates have attachments extracted (updates are always processed)."""
    trace_id = id_helpers.generate_id()

    # Create an initial trace without attachments
    trace = opik_client.trace(
        id=trace_id,
        name="test-trace-update-extraction",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={"prompt": "initial input"},
    )

    opik_client.flush()

    # Update the trace with attachment data
    trace.update(
        output={
            "result_image": _create_base64_url("image/png", constants.PNG_BASE64),
        }
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [len(base64.b64decode(constants.PNG_BASE64))]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__span_update__extracts_attachments(
    opik_client: opik.Opik,
):
    """Test that span updates have attachments extracted (updates are always processed)."""
    trace_id = id_helpers.generate_id()
    span_id = id_helpers.generate_id()

    # Create trace
    trace = opik_client.trace(
        id=trace_id,
        name="test-trace-for-span-update",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Create an initial span without attachments
    span = trace.span(
        id=span_id,
        name="test-span-update-extraction",
        input={"data": "initial"},
    )

    opik_client.flush()

    # Update the span with attachment data
    span.update(
        output={
            "chart": _create_base64_url("image/svg+xml", constants.SVG_BASE64),
        }
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [len(base64.b64decode(constants.SVG_BASE64))]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=span_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__various_file_types__all_extracted(
    opik_client: opik.Opik,
):
    """Test extraction of various file types (PNG, JPEG, PDF, GIF, WebP, SVG, JSON)."""
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-various-types",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={
            "png": _create_base64_url("image/png", constants.PNG_BASE64),
            "jpeg": _create_base64_url("image/jpeg", constants.JPEG_BASE64),
            "pdf": _create_base64_url("application/pdf", constants.PDF_BASE64),
            "gif": _create_base64_url("image/gif", constants.GIF89_BASE64),
            "webp": _create_base64_url("image/webp", constants.WEBP_BASE64),
            "svg": _create_base64_url("image/svg+xml", constants.SVG_BASE64),
            "json": _create_base64_url("application/json", constants.JSON_BASE64),
        },
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    # Verify attachments were extracted and uploaded
    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
        len(base64.b64decode(constants.JPEG_BASE64)),
        len(base64.b64decode(constants.PDF_BASE64)),
        len(base64.b64decode(constants.GIF89_BASE64)),
        len(base64.b64decode(constants.WEBP_BASE64)),
        len(base64.b64decode(constants.SVG_BASE64)),
        len(base64.b64decode(constants.JSON_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )


def test_extraction__backend_reinjects_extracted_attachments(
    opik_client: opik.Opik,
):
    """Test that backend reinjects extracted attachments."""
    trace_id = id_helpers.generate_id()
    span_id = id_helpers.generate_id()

    # Create a trace with end_time and base64-encoded images in input
    trace_input = {
        "image1": _create_base64_url("image/png", constants.PNG_BASE64),
        "text": "regular text field",
    }
    trace = opik_client.trace(
        id=trace_id,
        name="test-trace-backend_reinjects_extracted_attachments",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input=trace_input,
        end_time=datetime_helpers.local_timestamp(),
    )

    # Create a span with end_time and attachments
    span_input = {
        "image": _create_base64_url("image/png", constants.PNG_BASE64),
    }
    trace.span(
        id=span_id,
        name="test-span--backend_reinjects_extracted_attachments",
        input=span_input,
        end_time=datetime_helpers.local_timestamp(),
    )

    opik_client.flush()

    #
    # Verify attachments were extracted and uploaded for trace and span
    #
    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        expected_sizes=expected_sizes,
    )

    expected_sizes = [
        len(base64.b64decode(constants.PNG_BASE64)),
    ]
    verifiers.verify_auto_extracted_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=span_id,
        expected_sizes=expected_sizes,
    )

    #
    # Verify trace and span returned by backend has extracted attachments injected back into
    #

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="test-trace-backend_reinjects_extracted_attachments",
        input=trace_input,
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Verify span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span_id,
        parent_span_id=None,
        trace_id=trace_id,
        name="test-span--backend_reinjects_extracted_attachments",
        input=span_input,
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
