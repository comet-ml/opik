import re
import importlib
from unittest.mock import patch

import httpx
import pytest
import respx
import tenacity

from opik import httpx_client
from opik.file_upload import (
    file_uploader,
    file_upload_monitor,
    upload_options,
)
from opik.rest_client_configurator import retry_decorator
from . import conftest


@pytest.fixture
def file_to_upload(attachment):
    return upload_options.file_upload_options_from_attachment(attachment)


def test_upload_attachment__s3(file_to_upload, rest_client_s3, respx_mock):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    monitor = file_upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        upload_options=file_to_upload,
        rest_client=rest_client_s3,
        upload_httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
        monitor=monitor,
    )

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 3

    rest_client_s3.attachments.complete_multi_part_upload.assert_called_once()


def test_upload_attachment__s3__no_monitor(file_to_upload, rest_client_s3, respx_mock):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    file_uploader.upload_attachment(
        upload_options=file_to_upload,
        rest_client=rest_client_s3,
        upload_httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
    )

    route = respx.put(rx_url)
    assert route.call_count == 3

    rest_client_s3.attachments.complete_multi_part_upload.assert_called_once()


def test_upload_attachment__local(file_to_upload, rest_client_local, respx_mock):
    rx_url = re.compile("https://localhost:8080/bucket/*")
    respx_mock.put(rx_url).respond(200)

    monitor = file_upload_monitor.FileUploadMonitor()
    file_uploader.upload_attachment(
        upload_options=file_to_upload,
        rest_client=rest_client_local,
        upload_httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
        monitor=monitor,
    )

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 1


def test_upload_attachment__local__no_monitor(
    file_to_upload, rest_client_local, respx_mock
):
    rx_url = re.compile("https://localhost:8080/bucket/*")
    respx_mock.put(rx_url).respond(200)

    file_uploader.upload_attachment(
        upload_options=file_to_upload,
        rest_client=rest_client_local,
        upload_httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
    )

    route = respx.put(rx_url)
    assert route.call_count == 1


class TestUploadAttachmentRetry:
    # It is done as it is to patch retry decorator to minimize a retry interval
    def setup_method(self):
        opik_rest_retry = tenacity.retry(
            stop=tenacity.stop_after_attempt(3),
            wait=tenacity.wait_exponential(multiplier=1, min=1, max=2),
            retry=tenacity.retry_if_exception(retry_decorator._allowed_to_retry),
        )
        # Now patch the decorator where the decorator is being imported from
        patch(
            "opik.rest_client_configurator.retry_decorator.opik_rest_retry",
            lambda x: opik_rest_retry(x),
        ).start()
        # Reloads the module which applies our patched decorator
        importlib.reload(file_uploader.upload_client)

    def teardown_method(self):
        # Stops all patches started with start()
        patch.stopall()
        # Reload our module, which restores the original decorator
        importlib.reload(file_uploader.upload_client)

    def test_upload_attachment__local__retry_500(
        self, file_to_upload, rest_client_local, respx_mock
    ):
        rx_url = re.compile("https://localhost:8080/bucket/*")

        def retry_side_effect(request, route):
            if route.call_count < 1:
                return httpx.Response(500)
            else:
                return httpx.Response(200)

        respx_mock.put(rx_url).mock(side_effect=retry_side_effect)

        monitor = file_upload_monitor.FileUploadMonitor()
        file_uploader.upload_attachment(
            upload_options=file_to_upload,
            rest_client=rest_client_local,
            upload_httpx_client=httpx_client.get(
                None, None, check_tls_certificate=False, compress_json_requests=False
            ),
            monitor=monitor,
        )

        assert monitor.bytes_sent == conftest.FILE_SIZE

        route = respx.put(rx_url)
        assert route.call_count == 2  # one retry + upload call
