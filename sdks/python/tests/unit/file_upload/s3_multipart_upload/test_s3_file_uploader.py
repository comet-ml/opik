import re
import importlib
from unittest.mock import patch

import httpx
import pytest
import respx
import tenacity

from opik.file_upload import file_upload_monitor
from opik.file_upload.s3_multipart_upload import file_parts_strategy, s3_upload_error
from opik.file_upload.s3_multipart_upload import s3_file_uploader, s3_httpx_client
from .. import conftest


def test_upload_file_parts_to_s3(data_file, respx_mock):
    max_file_part_size = 5 * 1024 * 1024
    file_parts = file_parts_strategy.FilePartsStrategy(
        file_path=data_file.name,
        file_size=conftest.FILE_SIZE,
        max_file_part_size=max_file_part_size,
    )
    pre_sign_urls = [
        "https://s3.amazonaws.com/bucket/1",
        "https://s3.amazonaws.com/bucket/2",
        "https://s3.amazonaws.com/bucket/3",
    ]
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    httpx_client = s3_httpx_client.get()
    monitor = file_upload_monitor.FileUploadMonitor()

    uploader = s3_file_uploader.S3FileDataUploader(
        file_parts=file_parts,
        pre_sign_urls=pre_sign_urls,
        httpx_client=httpx_client,
        monitor=monitor,
    )

    # do upload and check results
    uploader.upload()

    assert monitor.bytes_sent == conftest.FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 3

    # check that collected metadata about uploaded parts is correct
    assert len(uploader.uploaded_parts) == 3
    for i, part in enumerate(uploader.uploaded_parts):
        assert part.e_tag == "e-tag"
        assert part.part_number == i + 1
        if i < 2:
            assert part.size == max_file_part_size
        else:
            assert part.size == conftest.FILE_SIZE - 2 * max_file_part_size


def test_upload_file_parts_to_s3__error_status(data_file, respx_mock):
    file_parts = file_parts_strategy.FilePartsStrategy(
        file_path=data_file.name,
        file_size=conftest.FILE_SIZE,
    )
    pre_sign_urls = [
        "https://s3.amazonaws.com/bucket/1",
        "https://s3.amazonaws.com/bucket/2",
        "https://s3.amazonaws.com/bucket/3",
    ]
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(403, headers={"ETag": "e-tag"})

    httpx_client = s3_httpx_client.get()
    monitor = file_upload_monitor.FileUploadMonitor()

    uploader = s3_file_uploader.S3FileDataUploader(
        file_parts=file_parts,
        pre_sign_urls=pre_sign_urls,
        httpx_client=httpx_client,
        monitor=monitor,
    )

    # do upload and check results
    with pytest.raises(s3_upload_error.S3UploadFileError):
        uploader.upload()


class TestS3FileDataUploaderRetry:
    # It is done as it is to patch retry decorator to minimize a retry interval
    def setup_method(self):
        s3_retry = tenacity.retry(
            stop=tenacity.stop_after_attempt(3),
            wait=tenacity.wait_exponential(multiplier=1, min=0.5, max=2),
            retry=tenacity.retry_if_exception(s3_httpx_client._allowed_to_retry),
        )
        # Now patch the decorator where the decorator is being imported from
        patch(
            "opik.file_upload.s3_multipart_upload.s3_httpx_client.s3_retry",
            lambda x: s3_retry(x),
        ).start()
        # Reloads the module which applies our patched decorator
        importlib.reload(s3_file_uploader)

    def teardown_method(self):
        # Stops all patches started with start()
        patch.stopall()
        # Reload our module, which restores the original decorator
        importlib.reload(s3_file_uploader)

    def test_upload_file_parts_to_s3__retry_on_500(self, data_file, respx_mock):
        max_file_part_size = 5 * 1024 * 1024
        file_parts = file_parts_strategy.FilePartsStrategy(
            file_path=data_file.name,
            file_size=conftest.FILE_SIZE,
            max_file_part_size=max_file_part_size,
        )
        pre_sign_urls = [
            "https://s3.amazonaws.com/bucket/1",
            "https://s3.amazonaws.com/bucket/2",
            "https://s3.amazonaws.com/bucket/3",
        ]
        rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")

        def retry_side_effect(request, route):
            if route.call_count < 1:
                return httpx.Response(500)
            else:
                return httpx.Response(200, headers={"ETag": "e-tag"})

        respx_mock.put(rx_url).mock(side_effect=retry_side_effect)

        httpx_client = s3_httpx_client.get()
        monitor = file_upload_monitor.FileUploadMonitor()

        uploader = s3_file_uploader.S3FileDataUploader(
            file_parts=file_parts,
            pre_sign_urls=pre_sign_urls,
            httpx_client=httpx_client,
            monitor=monitor,
        )

        # do upload and check results
        uploader.upload()

        assert monitor.bytes_sent == conftest.FILE_SIZE

        route = respx.put(rx_url)
        assert route.call_count == 3 + 1  # we have one retry due to 500
