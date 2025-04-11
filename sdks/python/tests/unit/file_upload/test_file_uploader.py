import re
from unittest import mock

import httpx
import pytest
import respx
import uuid6

from opik import httpx_client
from opik.file_upload import file_uploader, upload_client, upload_monitor
from opik.message_processing import messages
from opik.rest_api import types as rest_api_types
from . import conftest


@pytest.fixture
def attachment(data_file):
    attachment = messages.CreateAttachmentMessage(
        file_path=data_file.name,
        file_name="test_attachment.dat",
        mime_type="application/octet-stream",
        entity_type="span",
        entity_id="entity_id",
        project_name="project_name",
        base_url_path="base_url_path",
    )
    yield attachment


@pytest.fixture
def rest_client_s3():
    # returns response to start_upload request initiating S3 upload
    pre_sign_urls = [
        "https://s3.amazonaws.com/bucket/1",
        "https://s3.amazonaws.com/bucket/2",
        "https://s3.amazonaws.com/bucket/3",
    ]
    start_upload_response = rest_api_types.StartMultipartUploadResponse(
        upload_id=str(uuid6.uuid7()), pre_sign_urls=pre_sign_urls
    )
    rest_client = mock.Mock()
    rest_client.attachments.start_multi_part_upload.return_value = start_upload_response

    yield rest_client


@pytest.fixture
def rest_client_local():
    pre_sign_urls = [
        "https://localhost:8080/bucket/1",
    ]
    start_upload_response = rest_api_types.StartMultipartUploadResponse(
        upload_id=upload_client.LOCAL_UPLOAD_MAGIC_ID, pre_sign_urls=pre_sign_urls
    )
    rest_client = mock.Mock()
    rest_client.attachments.start_multi_part_upload.return_value = start_upload_response

    yield rest_client


def test_upload_attachment__s3(attachment, rest_client_s3, respx_mock):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    monitor = upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        attachment=attachment,
        rest_client=rest_client_s3,
        httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
        monitor=monitor,
    )

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 3

    rest_client_s3.attachments.complete_multi_part_upload.assert_called_once()


def test_upload_attachment__s3__no_monitor(attachment, rest_client_s3, respx_mock):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    file_uploader.upload_attachment(
        attachment=attachment,
        rest_client=rest_client_s3,
        httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
    )

    route = respx.put(rx_url)
    assert route.call_count == 3

    rest_client_s3.attachments.complete_multi_part_upload.assert_called_once()


def test_upload_attachment__local(attachment, rest_client_local, respx_mock):
    rx_url = re.compile("https://localhost:8080/bucket/*")
    respx_mock.put(rx_url).respond(200)

    monitor = upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        attachment=attachment,
        rest_client=rest_client_local,
        httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
        monitor=monitor,
    )

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 1


def test_upload_attachment__local__no_monitor(
    attachment, rest_client_local, respx_mock
):
    rx_url = re.compile("https://localhost:8080/bucket/*")
    respx_mock.put(rx_url).respond(200)

    file_uploader.upload_attachment(
        attachment=attachment,
        rest_client=rest_client_local,
        httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
    )

    route = respx.put(rx_url)
    assert route.call_count == 1


def test_upload_attachment__local__retry_500(attachment, rest_client_local, respx_mock):
    rx_url = re.compile("https://localhost:8080/bucket/*")

    def retry_side_effect(request, route):
        if route.call_count < 1:
            return httpx.Response(500)
        else:
            return httpx.Response(200)

    respx_mock.put(rx_url).mock(side_effect=retry_side_effect)

    monitor = upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        attachment=attachment,
        rest_client=rest_client_local,
        httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
        monitor=monitor,
    )

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 2  # one retry + upload call
