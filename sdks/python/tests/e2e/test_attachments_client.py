import os

import pytest

import opik
from opik import Attachment, id_helpers
from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME, ATTACHMENT_FILE_SIZE
from opik.rest_api import core as rest_api_core
from opik import synchronization


def test_attachments_client__get_attachment_list_for_trace__happyflow(
    opik_client: opik.Opik, attachment_data_file
):
    trace_id = id_helpers.generate_id()

    file_name = os.path.basename(attachment_data_file.name)
    attachment = Attachment(
        data=attachment_data_file.name,
        file_name=file_name,
        content_type="application/octet-stream",
    )

    opik_client.trace(
        id=trace_id,
        name="test-trace-with-attachment",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        attachments=[attachment],
    )

    opik_client.flush()

    attachments_client = opik_client.get_attachment_client()

    synchronization.wait_for_done(
        lambda: len(
            attachments_client.get_attachment_list(
                project_name=OPIK_E2E_TESTS_PROJECT_NAME,
                entity_id=trace_id,
                entity_type="trace",
            )
        )
        > 0,
        timeout=30,
    )

    attachments_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=trace_id,
        entity_type="trace",
    )
    assert len(attachments_list) == 1
    assert attachments_list[0].file_name == file_name
    assert attachments_list[0].mime_type == "application/octet-stream"


def test_attachments_client__download_attachment_for_trace__happyflow(
    opik_client: opik.Opik, attachment_data_file
):
    trace_id = id_helpers.generate_id()

    file_name = os.path.basename(attachment_data_file.name)
    attachment = Attachment(
        data=attachment_data_file.name,
        file_name=file_name,
        content_type="text/plain",
    )

    opik_client.trace(
        id=trace_id,
        name="test-trace-download-attachment",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        attachments=[attachment],
    )

    opik_client.flush()
    attachments_client = opik_client.get_attachment_client()

    synchronization.wait_for_done(
        lambda: len(
            attachments_client.get_attachment_list(
                project_name=OPIK_E2E_TESTS_PROJECT_NAME,
                entity_id=trace_id,
                entity_type="trace",
            )
        )
        > 0,
        timeout=30,
    )

    attachment_data = attachments_client.download_attachment(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_type="trace",
        entity_id=trace_id,
        file_name=file_name,
        mime_type="text/plain",
    )
    downloaded_content = b"".join(attachment_data)

    # Read the original file content to compare
    attachment_data_file.seek(0)
    expected_content = attachment_data_file.read()
    assert downloaded_content == expected_content


def test_attachments_client__get_attachment_list_for_span__happyflow(
    opik_client: opik.Opik, attachment_data_file
):
    span_id = id_helpers.generate_id()
    trace_id = id_helpers.generate_id()

    file_name = os.path.basename(attachment_data_file.name)
    attachment = Attachment(
        data=attachment_data_file.name,
        file_name=file_name,
        content_type="application/octet-stream",
    )

    opik_client.trace(
        id=trace_id,
        name="test-trace-for-span",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    opik_client.span(
        id=span_id,
        trace_id=trace_id,
        name="test-span-with-attachment",
        attachments=[attachment],
    )

    opik_client.flush()

    attachments_client = opik_client.get_attachment_client()

    synchronization.wait_for_done(
        lambda: len(
            attachments_client.get_attachment_list(
                project_name=OPIK_E2E_TESTS_PROJECT_NAME,
                entity_id=span_id,
                entity_type="span",
            )
        )
        > 0,
        timeout=30,
    )

    attachments_list = attachments_client.get_attachment_list(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_id=span_id,
        entity_type="span",
    )
    assert len(attachments_list) == 1
    assert attachments_list[0].file_name == file_name
    assert attachments_list[0].mime_type == "application/octet-stream"


def test_attachments_client__invalid_project_name__or_non_existing_entity_id__raises_error(
    opik_client: opik.Opik,
):
    attachments_client = opik_client.get_attachment_client()

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
    opik_client: opik.Opik, attachment_data_file
):
    """Test uploading an attachment for a trace."""
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-for-upload",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )

    opik_client.flush()

    attachments_client = opik_client.get_attachment_client()

    file_name = os.path.basename(attachment_data_file.name)
    attachments_client.upload_attachment(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_type="trace",
        entity_id=trace_id,
        file_path=attachment_data_file.name,
        file_name=file_name,
        mime_type="application/octet-stream",
    )

    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="trace",
        entity_id=trace_id,
        attachments={
            file_name: Attachment(
                data=attachment_data_file.name,
                file_name=file_name,
                content_type="application/octet-stream",
            )
        },
        data_sizes={file_name: ATTACHMENT_FILE_SIZE},
    )


def test_attachments_client__upload_attachment_for_span__happyflow(
    opik_client: opik.Opik, attachment_data_file
):
    """Test uploading an attachment for a span."""
    span_id = id_helpers.generate_id()
    trace_id = id_helpers.generate_id()

    opik_client.trace(
        id=trace_id,
        name="test-trace-for-span-upload",
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
    )
    opik_client.span(
        id=span_id,
        trace_id=trace_id,
        name="test-span-for-upload",
    )

    opik_client.flush()

    attachments_client = opik_client.get_attachment_client()

    file_name = os.path.basename(attachment_data_file.name)
    attachments_client.upload_attachment(
        project_name=OPIK_E2E_TESTS_PROJECT_NAME,
        entity_type="span",
        entity_id=span_id,
        file_path=attachment_data_file.name,
        file_name=file_name,
        mime_type="application/octet-stream",
    )

    verifiers.verify_attachments(
        opik_client=opik_client,
        entity_type="span",
        entity_id=span_id,
        attachments={
            file_name: Attachment(
                data=attachment_data_file.name,
                file_name=file_name,
                content_type="application/octet-stream",
            )
        },
        data_sizes={file_name: ATTACHMENT_FILE_SIZE},
    )
