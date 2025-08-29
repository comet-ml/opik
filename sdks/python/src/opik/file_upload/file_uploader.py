import logging
from typing import Optional

import httpx

from . import upload_client, file_upload_monitor
from . import upload_options as file_upload_options
from .. import format_helpers, s3_httpx_client
from .s3_multipart_upload import file_parts_strategy, s3_file_uploader
from ..rest_api import client as rest_api_client
from ..rest_api import types as rest_api_types

LOGGER = logging.getLogger(__name__)

UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024


def upload_attachment(
    upload_options: file_upload_options.FileUploadOptions,
    rest_client: rest_api_client.OpikApi,
    upload_httpx_client: httpx.Client,
    monitor: Optional[file_upload_monitor.FileUploadMonitor] = None,
) -> None:
    try:
        _do_upload_attachment(
            upload_options=upload_options,
            rest_client=rest_client,
            httpx_client=upload_httpx_client,
            monitor=monitor,
        )
    except Exception as e:
        LOGGER.error(
            "Failed to upload attachment: '%s' from file: [%s] with size: [%s]. Error: %s",
            upload_options.file_name,
            upload_options.file_path,
            format_helpers.format_bytes(upload_options.file_size),
            e,
            exc_info=True,
        )
        raise


def _do_upload_attachment(
    upload_options: file_upload_options.FileUploadOptions,
    rest_client: rest_api_client.OpikApi,
    httpx_client: httpx.Client,
    monitor: Optional[file_upload_monitor.FileUploadMonitor],
) -> None:
    file_parts = file_parts_strategy.FilePartsStrategy(
        file_path=upload_options.file_path,
        file_size=upload_options.file_size,
        max_file_part_size=file_parts_strategy.MIN_FILE_PART_SIZE,
    )
    parts_number = file_parts.calculate()

    LOGGER.debug(
        f"Initiating multipart upload for {parts_number} parts of: '{upload_options.file_name}' file"
    )

    upload_rest_client = upload_client.RestFileUploadClient(
        rest_client=rest_client, httpx_client=httpx_client
    )
    upload_metadata = upload_rest_client.start_upload(
        upload_options=upload_options,
        num_of_file_parts=parts_number,
        base_url_path=upload_options.encoded_url_override,
    )
    assert len(upload_metadata.urls) > 0, "At least one URL must be returned by backend"

    if upload_metadata.should_use_s3_uploader():
        LOGGER.debug(
            f"Starting attachment upload `{upload_options.file_name}` to S3 directly."
        )
        upload_to_s3_directly(
            upload_rest_client=upload_rest_client,
            file_parts=file_parts,
            upload_metadata=upload_metadata,
            upload_options=upload_options,
            monitor=monitor,
        )
        LOGGER.debug(
            f"Attachment '{upload_options.file_name}' [{upload_options.file_size} bytes] is uploaded to S3."
        )
    else:
        LOGGER.debug(
            f"Starting attachment upload `{upload_options.file_name}` to backend."
        )
        upload_rest_client.upload_file_local(
            upload_url=upload_metadata.urls[0],
            file_path=upload_options.file_path,
            chunk_size=UPLOAD_CHUNK_SIZE,
            monitor=monitor,
        )
        LOGGER.debug(
            f"Attachment '{upload_options.file_name}' [{upload_options.file_size} bytes] is uploaded to backend."
        )


def upload_to_s3_directly(
    upload_rest_client: upload_client.RestFileUploadClient,
    file_parts: file_parts_strategy.FilePartsStrategy,
    upload_metadata: upload_client.MultipartUploadMetadata,
    upload_options: file_upload_options.FileUploadOptions,
    monitor: Optional[file_upload_monitor.FileUploadMonitor],
) -> None:
    s3_uploader = s3_file_uploader.S3FileDataUploader(
        file_parts=file_parts,
        pre_sign_urls=upload_metadata.urls,
        httpx_client=s3_httpx_client.get_cached(),
        monitor=monitor,
    )
    uploaded_parts = s3_uploader.upload()

    sent_file_parts = [
        rest_api_types.MultipartUploadPart(
            e_tag=part.e_tag, part_number=part.part_number
        )
        for part in uploaded_parts
    ]
    LOGGER.debug(
        f"Sent: {s3_uploader.bytes_sent} / {file_parts.file_size} bytes of file {file_parts.file} in {len(sent_file_parts)} parts."
    )

    upload_rest_client.s3_upload_completed(
        upload_options=upload_options,
        upload_metadata=upload_metadata,
        file_parts=sent_file_parts,
    )
