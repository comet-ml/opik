import random
import re
import tempfile

import httpx
import pytest
import respx

from opik.file_upload.s3_multipart_upload import file_parts_strategy, s3_upload_error
from opik.file_upload.s3_multipart_upload import s3_file_uploader, s3_httpx_client
from opik.file_upload import upload_monitor

FILE_SIZE = 12 * 1024 * 1024


@pytest.fixture
def data_file():
    with tempfile.NamedTemporaryFile(delete=True) as file:
        file.write(random.randbytes(FILE_SIZE))
        file.seek(0)

        yield file


def test_upload_file_parts_to_s3(data_file, respx_mock):
    max_file_part_size = 5 * 1024 * 1024
    file_parts = file_parts_strategy.FilePartsStrategy(
        file_path=data_file.name,
        file_size=FILE_SIZE,
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
    monitor = upload_monitor.FileUploadMonitor()

    uploader = s3_file_uploader.S3FileDataUploader(
        file_parts=file_parts,
        pre_sign_urls=pre_sign_urls,
        httpx_client=httpx_client,
        monitor=monitor,
    )

    # do upload and check results
    uploader.upload()

    assert monitor.bytes_sent == FILE_SIZE

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
            assert part.size == FILE_SIZE - 2 * max_file_part_size


def test_upload_file_parts_to_s3__retry_on_500(data_file, respx_mock):
    max_file_part_size = 5 * 1024 * 1024
    file_parts = file_parts_strategy.FilePartsStrategy(
        file_path=data_file.name,
        file_size=FILE_SIZE,
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
    monitor = upload_monitor.FileUploadMonitor()

    uploader = s3_file_uploader.S3FileDataUploader(
        file_parts=file_parts,
        pre_sign_urls=pre_sign_urls,
        httpx_client=httpx_client,
        monitor=monitor,
    )

    # do upload and check results
    uploader.upload()

    assert monitor.bytes_sent == FILE_SIZE

    route = respx.put(rx_url)
    assert route.call_count == 3 + 1  # we have one retry due to 500


def test_upload_file_parts_to_s3__error_status(data_file, respx_mock):
    file_parts = file_parts_strategy.FilePartsStrategy(
        file_path=data_file.name,
        file_size=FILE_SIZE,
    )
    pre_sign_urls = [
        "https://s3.amazonaws.com/bucket/1",
        "https://s3.amazonaws.com/bucket/2",
        "https://s3.amazonaws.com/bucket/3",
    ]
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(403, headers={"ETag": "e-tag"})

    httpx_client = s3_httpx_client.get()
    monitor = upload_monitor.FileUploadMonitor()

    uploader = s3_file_uploader.S3FileDataUploader(
        file_parts=file_parts,
        pre_sign_urls=pre_sign_urls,
        httpx_client=httpx_client,
        monitor=monitor,
    )

    # do upload and check results
    with pytest.raises(s3_upload_error.S3UploadFileError):
        uploader.upload()
