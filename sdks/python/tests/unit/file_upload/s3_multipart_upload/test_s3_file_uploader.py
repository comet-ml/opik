import re
from unittest.mock import patch

import httpx
import pytest
import respx
import tenacity

from opik.file_upload import file_upload_monitor
from opik.file_upload.s3_multipart_upload import file_parts_strategy, s3_upload_error
from opik.file_upload.s3_multipart_upload import s3_file_uploader
from opik import s3_httpx_client
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

    route = respx.put(rx_url)
    assert route.call_count == 1


class TestS3FileDataUploaderRetry:
    # Patch only the wait strategy so tests exercise the production retry policy.
    def setup_method(self):
        patch.object(
            s3_file_uploader.S3FileDataUploader._send_data_part.retry,
            "wait",
            tenacity.wait_none(),
        ).start()

    def teardown_method(self):
        patch.stopall()

    @pytest.mark.parametrize("status_code", [500, 502, 503, 504])
    def test_upload_file_parts_to_s3__retryable_status__retries(
        self, data_file, respx_mock, status_code
    ):
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
        requests: list[tuple[httpx.URL, bytes]] = []

        def retry_side_effect(request, route):
            requests.append((request.url, request.content))
            if route.call_count < 1:
                return httpx.Response(status_code)
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
        assert route.call_count == 3 + 1
        assert requests[0] == requests[1]

    def test_upload_file_parts_to_s3__remote_protocol_error__retries(
        self, data_file, respx_mock
    ):
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
        requests: list[tuple[httpx.URL, bytes]] = []

        def retry_side_effect(request, route):
            requests.append((request.url, request.content))
            if route.call_count < 1:
                raise httpx.RemoteProtocolError(
                    "Server disconnected without sending a response",
                    request=request,
                )
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

        uploader.upload()

        assert monitor.bytes_sent == conftest.FILE_SIZE

        route = respx.put(rx_url)
        assert route.call_count == 3 + 1
        assert requests[0] == requests[1]

    def test_upload_file_parts_to_s3__remote_protocol_error_exhausted__retains_for_replay(
        self, data_file, respx_mock
    ):
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

        def remote_protocol_error(request, route):
            raise httpx.RemoteProtocolError(
                "Server disconnected without sending a response",
                request=request,
            )

        respx_mock.put(rx_url).mock(side_effect=remote_protocol_error)

        uploader = s3_file_uploader.S3FileDataUploader(
            file_parts=file_parts,
            pre_sign_urls=pre_sign_urls,
            httpx_client=s3_httpx_client.get(),
        )

        with pytest.raises(s3_upload_error.S3UploadFileError) as exc_info:
            uploader.upload()

        upload_error = exc_info.value
        assert upload_error.connection_error is True
        assert isinstance(upload_error.__cause__, httpx.RemoteProtocolError)
        assert str(upload_error.__cause__) == (
            "Server disconnected without sending a response"
        )

        route = respx.put(rx_url)
        assert route.call_count == 3
