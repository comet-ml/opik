import re

import httpx
import respx

from opik import httpx_client
from opik.file_upload import (
    file_uploader,
    upload_monitor,
)
from . import conftest


def test_upload_attachment__s3(attachment, rest_client_s3, respx_mock):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    monitor = upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        upload_options=attachment,
        rest_client=rest_client_s3,
        upload_httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
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
        upload_options=attachment,
        rest_client=rest_client_s3,
        upload_httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
    )

    route = respx.put(rx_url)
    assert route.call_count == 3

    rest_client_s3.attachments.complete_multi_part_upload.assert_called_once()


def test_upload_attachment__local(attachment, rest_client_local, respx_mock):
    rx_url = re.compile("https://localhost:8080/bucket/*")
    respx_mock.put(rx_url).respond(200)

    monitor = upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        upload_options=attachment,
        rest_client=rest_client_local,
        upload_httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
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
        upload_options=attachment,
        rest_client=rest_client_local,
        upload_httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
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
        upload_options=attachment,
        rest_client=rest_client_local,
        upload_httpx_client=httpx_client.get(None, None, check_tls_certificate=False),
        monitor=monitor,
    )

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 2  # one retry + upload call
