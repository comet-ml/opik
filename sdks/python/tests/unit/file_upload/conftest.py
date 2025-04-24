import tempfile
from unittest import mock

import numpy as np
import pytest
import uuid6

from opik.file_upload import (
    upload_client,
)
from opik.message_processing import messages
from opik.rest_api import types as rest_api_types

FILE_SIZE = 12 * 1024 * 1024


@pytest.fixture
def data_file():
    with tempfile.NamedTemporaryFile(delete=True) as file:
        file.write(np.random.bytes(FILE_SIZE))
        file.seek(0)

        yield file


@pytest.fixture
def attachment(data_file):
    attachment = messages.CreateAttachmentMessage(
        file_path=data_file.name,
        file_name="test_attachment.dat",
        mime_type="application/octet-stream",
        entity_type="span",
        entity_id="entity_id",
        project_name="project_name",
        encoded_url_override="encoded_url_override_path",
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
