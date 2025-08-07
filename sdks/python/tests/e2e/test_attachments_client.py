import os
import tempfile

import pytest

import opik
from opik import Attachment, id_helpers
from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME
from opik.rest_api import core as rest_api_core


@pytest.fixture
def data_file():
    with tempfile.NamedTemporaryFile(mode="w", delete=False) as f:
        f.write("test content for attachment")
        f.flush()
        yield f
    os.unlink(f.name)


def test_attachments_client__get_attachment_list__happyflow(
    opik_client: opik.Opik, data_file
):
    trace_id = id_helpers.generate_id()

    file_name = os.path.basename(data_file.name)
    attachment = Attachment(
        data=data_file.name,
        file_name=file_name,
        content_type="application/octet-stream",
    )

    opik_client.trace(
        id=trace_id,
        name="test-trace-with-attachment",
        input={"input": "test input"},
        output={"output": "test output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        attachments=[attachment],
    )

    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        name="test-trace-with-attachment",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    attachments_client = opik_client.get_attachments_client()

    attachments_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=trace_id,
        entity_type="trace",
    )
    assert len(attachments_list) == 1
    assert attachments_list[0].file_name == file_name
    assert attachments_list[0].mime_type == "application/octet-stream"


def test_attachments_client__download_attachment__happyflow(
    opik_client: opik.Opik, data_file
):
    trace_id = id_helpers.generate_id()

    file_name = os.path.basename(data_file.name)
    attachment = Attachment(
        data=data_file.name,
        file_name=file_name,
        content_type="text/plain",
    )

    opik_client.trace(
        id=trace_id,
        name="test-trace-download-attachment",
        input={"input": "test input"},
        output={"output": "test output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        attachments=[attachment],
    )

    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        name="test-trace-download-attachment",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    attachments_client = opik_client.get_attachments_client()

    attachment_data = attachments_client.download_attachment(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_type="trace",
        entity_id=trace_id,
        file_name=file_name,
        mime_type="text/plain",
    )
    downloaded_content = b"".join(attachment_data)
    assert downloaded_content == b"test content for attachment"


def test_attachments_client__get_attachment_list_for_span__happyflow(
    opik_client: opik.Opik, data_file
):
    span_id = id_helpers.generate_id()
    trace_id = id_helpers.generate_id()

    file_name = os.path.basename(data_file.name)
    attachment = Attachment(
        data=data_file.name,
        file_name=file_name,
        content_type="application/json",
    )

    opik_client.trace(
        id=trace_id,
        name="test-trace-for-span",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={"trace_input": "test"},
        output={"trace_output": "test"},
    )
    opik_client.span(
        id=span_id,
        trace_id=trace_id,
        name="test-span-with-attachment",
        input={"span_input": "test"},
        output={"span_output": "test"},
        attachments=[attachment],
    )

    opik_client.flush()

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span_id,
        trace_id=trace_id,
        parent_span_id=None,
        name="test-span-with-attachment",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    attachments_client = opik_client.get_attachments_client()

    attachments_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=span_id,
        entity_type="span",
    )
    assert len(attachments_list) == 1
    assert attachments_list[0].file_name == file_name
    assert attachments_list[0].mime_type == "application/json"


def test_attachments_client__invalid_project_name__or_non_existing_entity_id__raises_error(
    opik_client: opik.Opik,
):
    attachments_client = opik_client.get_attachments_client()

    with pytest.raises(rest_api_core.ApiError):
        attachments_client.get_attachment_list(
            project_name="non-existent-project",
            entity_id="some-entity-id",
            entity_type="trace",
        )

    with pytest.raises(rest_api_core.ApiError):
        attachments_client.get_attachment_list(
            project_name=OPIK_E2E_TESTS_PROJECT_NAME,
            entity_id="non-existent-entity-id",
            entity_type="trace",
        )


def test_attachments_client__upload_attachment_for_trace__happyflow(
    opik_client: opik.Opik, data_file
):
    """Test uploading an attachment for a trace."""
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-for-upload",
        input={"input": "test input"},
        output={"output": "test output"},
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        name="test-trace-for-upload",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    attachments_client = opik_client.get_attachments_client()

    file_name = os.path.basename(data_file.name)
    attachments_client.upload_attachment(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_type="trace",
        entity_id=trace_id,
        file_path=data_file.name,
        file_name=file_name,
        mime_type="text/plain",
    )

    attachments_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=trace_id,
        entity_type="trace",
    )

    assert len(attachments_list) == 1
    assert attachments_list[0].file_name == file_name
    assert attachments_list[0].mime_type == "text/plain"


def test_attachments_client__upload_attachment_for_span__happyflow(
    opik_client: opik.Opik, data_file
):
    """Test uploading an attachment for a span."""
    span_id = id_helpers.generate_id()
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-for-span-upload",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        input={"trace_input": "test"},
        output={"trace_output": "test"},
    )
    opik_client.span(
        id=span_id,
        trace_id=trace_id,
        name="test-span-for-upload",
        input={"span_input": "test"},
        output={"span_output": "test"},
    )

    opik_client.flush()

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span_id,
        trace_id=trace_id,
        parent_span_id=None,
        name="test-span-for-upload",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    attachments_client = opik_client.get_attachments_client()

    file_name = os.path.basename(data_file.name)
    attachments_client.upload_attachment(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_type="span",
        entity_id=span_id,
        file_path=data_file.name,
        file_name=file_name,
        mime_type="application/json",
    )

    attachments_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=span_id,
        entity_type="span",
    )

    assert len(attachments_list) == 1
    assert attachments_list[0].file_name == file_name
    assert attachments_list[0].mime_type == "application/json"
