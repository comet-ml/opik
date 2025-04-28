import re

import httpx
import respx

from opik import httpx_client
from opik.file_upload import (
    upload_manager,
)


def test_upload_attachment__s3(rest_client_s3, attachment, respx_mock, capture_log):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")
    respx_mock.put(rx_url).respond(200, headers={"ETag": "e-tag"})

    file_upload_manager = upload_manager.FileUploadManager(
        rest_client=rest_client_s3,
        httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
        worker_count=1,
    )
    monitor = upload_manager.FileUploadManagerMonitor(file_upload_manager)

    number_of_uploads = 10
    for _ in range(number_of_uploads):
        file_upload_manager.upload_attachment(attachment)

    file_upload_manager.close()

    monitor.log_remaining_uploads()

    assert file_upload_manager.all_done() is True
    assert file_upload_manager.remaining_data().uploads == 0
    assert file_upload_manager.failed_uploads(1) == 0
    assert monitor.all_done() is True

    route = respx.put(rx_url)
    assert route.call_count == 3 * number_of_uploads

    assert (
        rest_client_s3.attachments.complete_multi_part_upload.call_count
        == number_of_uploads
    )

    assert (
        "All assets have been sent, waiting for delivery confirmation"
        in capture_log.messages
    )


def test_upload_attachment__s3__failed_upload(rest_client_s3, attachment, respx_mock):
    rx_url = re.compile("https://s3\\.amazonaws\\.com/bucket/*")

    failed_count = 1

    def retry_side_effect(request, route):
        if route.call_count < failed_count:
            return httpx.Response(400)
        else:
            return httpx.Response(200, headers={"ETag": "e-tag"})

    respx_mock.put(rx_url).mock(side_effect=retry_side_effect)

    file_upload_manager = upload_manager.FileUploadManager(
        rest_client=rest_client_s3,
        httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
        worker_count=1,
    )
    monitor = upload_manager.FileUploadManagerMonitor(file_upload_manager)

    number_of_uploads = 10
    for _ in range(number_of_uploads):
        file_upload_manager.upload_attachment(attachment)

    file_upload_manager.close()

    monitor.log_remaining_uploads()

    assert monitor.all_done() is True
    assert file_upload_manager.all_done() is True
    assert file_upload_manager.remaining_data().uploads == 0

    assert file_upload_manager.failed_uploads(1) == failed_count

    route = respx.put(rx_url)
    # the first upload will fail - so, only one call to bucket instead of three for it
    # plus remaining successful calls
    assert route.call_count == (3 * (number_of_uploads - 1)) + 1


def test_upload_attachment__local(
    attachment, rest_client_local, respx_mock, capture_log
):
    rx_url = re.compile("https://localhost:8080/bucket/*")
    respx_mock.put(rx_url).respond(200)

    file_upload_manager = upload_manager.FileUploadManager(
        rest_client=rest_client_local,
        httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
        worker_count=1,
    )
    monitor = upload_manager.FileUploadManagerMonitor(file_upload_manager)

    number_of_uploads = 10
    for _ in range(number_of_uploads):
        file_upload_manager.upload_attachment(attachment)

    file_upload_manager.close()

    monitor.log_remaining_uploads()

    assert file_upload_manager.all_done() is True
    assert file_upload_manager.remaining_data().uploads == 0
    assert file_upload_manager.failed_uploads(1) == 0
    assert monitor.all_done() is True

    route = respx.put(rx_url)
    assert route.call_count == number_of_uploads

    assert (
        "All assets have been sent, waiting for delivery confirmation"
        in capture_log.messages
    )


def test_upload_attachment__local__failed_upload(
    attachment, rest_client_local, respx_mock
):
    rx_url = re.compile("https://localhost:8080/bucket/*")

    failed_count = 2

    def retry_side_effect(request, route):
        if route.call_count < failed_count:
            return httpx.Response(400)
        else:
            return httpx.Response(200)

    respx_mock.put(rx_url).mock(side_effect=retry_side_effect)

    file_upload_manager = upload_manager.FileUploadManager(
        rest_client=rest_client_local,
        httpx_client=httpx_client.get(
            None, None, check_tls_certificate=False, compress_json_requests=False
        ),
        worker_count=1,
    )
    monitor = upload_manager.FileUploadManagerMonitor(file_upload_manager)

    number_of_uploads = 10
    for _ in range(number_of_uploads):
        file_upload_manager.upload_attachment(attachment)

    file_upload_manager.close()

    monitor.log_remaining_uploads()

    assert file_upload_manager.all_done() is True
    assert file_upload_manager.remaining_data().uploads == 0
    assert monitor.all_done() is True

    assert file_upload_manager.failed_uploads(1) == failed_count

    route = respx.put(rx_url)
    assert route.call_count == number_of_uploads
