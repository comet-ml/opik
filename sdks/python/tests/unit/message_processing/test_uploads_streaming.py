import time
from typing import Tuple, Any, Generator
from unittest import mock
from unittest.mock import sentinel, patch

import pytest

from opik.file_upload import upload_manager
from opik.message_processing import messages, streamer, streamer_constructors
from opik.message_processing.processors import online_message_processor

NOT_USED = sentinel.NOT_USED


@pytest.fixture
def streamer_with_file_upload_manager(
    request,
) -> Generator[Tuple[streamer.Streamer, upload_manager.FileUploadManager], Any, None]:
    def upload_noop(**kwargs):
        print("Uploading file")
        time.sleep(request.param)

    with patch("opik.file_upload.upload_manager.file_uploader") as uploader_mock:
        uploader_mock.upload_attachment = upload_noop

        file_upload_manager = upload_manager.FileUploadManager(
            rest_client=mock.Mock(), httpx_client=mock.Mock(), worker_count=2
        )
        online = online_message_processor.OpikMessageProcessor(
            rest_client=mock.Mock(),
            file_upload_manager=file_upload_manager,
            fallback_replay_manager=mock.Mock(),
        )
        streamer_ = streamer_constructors.construct_streamer(
            message_processor=online,
            n_consumers=1,
            use_batching=True,
            use_attachment_extraction=False,
            file_uploader=file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=mock.Mock(),
        )

        yield streamer_, file_upload_manager


@pytest.mark.parametrize("streamer_with_file_upload_manager", [0.5], indirect=True)
def test_streamer__flush__attachment_uploads__ok(
    streamer_with_file_upload_manager, temp_file_15mb
):
    message_streamer, file_upload_manager = streamer_with_file_upload_manager

    attachment = messages.CreateAttachmentMessage(
        file_path=temp_file_15mb.name,
        file_name="test_file",
        mime_type=None,
        entity_type="span",
        entity_id=NOT_USED,
        project_name=NOT_USED,
        encoded_url_override=NOT_USED,
    )

    message_streamer.put(attachment)
    message_streamer.put(attachment)

    # we have timeout greater than upload time 1 > 0.5, thus all uploads will be completed
    assert message_streamer.flush(timeout=1, upload_sleep_time=1) is True

    assert file_upload_manager.remaining_data().uploads == 0


@pytest.mark.parametrize("streamer_with_file_upload_manager", [0.5], indirect=True)
def test_streamer__flush__attachment_uploads__timeout(
    streamer_with_file_upload_manager, temp_file_15mb
):
    message_streamer, file_upload_manager = streamer_with_file_upload_manager

    attachment = messages.CreateAttachmentMessage(
        file_path=temp_file_15mb.name,
        file_name="test_file",
        mime_type=None,
        entity_type="span",
        entity_id=NOT_USED,
        project_name=NOT_USED,
        encoded_url_override=NOT_USED,
    )

    message_streamer.put(attachment)
    message_streamer.put(attachment)

    # we have timeout less than upload time 0.1 < 0.5, thus not all uploads will be completed
    assert message_streamer.flush(timeout=0.1, upload_sleep_time=0.1) is False

    assert file_upload_manager.remaining_data().uploads == 2
