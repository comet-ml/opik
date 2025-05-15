import time
from unittest import mock
from unittest.mock import sentinel, patch

from opik.message_processing import messages, streamer_constructors
from opik.file_upload import upload_manager

NOT_USED = sentinel.NOT_USED


def test_streamer__attachment_uploads__flush__ok(temp_file_15mb):
    def upload_noop(**kwargs):
        print("Uploading file")
        time.sleep(1)

    with patch("opik.file_upload.upload_manager.file_uploader") as uploader_mock:
        uploader_mock.upload_attachment = upload_noop

        file_upload_manager = upload_manager.FileUploadManager(
            rest_client=mock.Mock(), httpx_client=mock.Mock(), worker_count=2
        )
        tested = streamer_constructors.construct_streamer(
            message_processor=mock.Mock(),
            n_consumers=1,
            use_batching=True,
            file_upload_manager=file_upload_manager,
            max_queue_size=None,
        )

        attachment = messages.CreateAttachmentMessage(
            file_path=temp_file_15mb.name,
            file_name="test_file",
            mime_type=None,
            entity_type="span",
            entity_id=NOT_USED,
            project_name=NOT_USED,
            encoded_url_override=NOT_USED,
        )

        tested.put(attachment)
        tested.put(attachment)

        assert file_upload_manager.remaining_data().uploads == 2

        # we have timeout greater than upload time 2 > 1, thus all uploads will be completed
        assert tested.flush(timeout=2, upload_sleep_time=1) is True

        assert file_upload_manager.remaining_data().uploads == 0


def test_streamer__attachment_uploads__flush__timeout(temp_file_15mb):
    def upload_noop(**kwargs):
        print("Uploading file")
        time.sleep(2)

    with patch("opik.file_upload.upload_manager.file_uploader") as uploader_mock:
        uploader_mock.upload_attachment = upload_noop

        file_upload_manager = upload_manager.FileUploadManager(
            rest_client=mock.Mock(), httpx_client=mock.Mock(), worker_count=2
        )
        tested = streamer_constructors.construct_streamer(
            message_processor=mock.Mock(),
            n_consumers=1,
            use_batching=True,
            file_upload_manager=file_upload_manager,
            max_queue_size=None,
        )

        attachment = messages.CreateAttachmentMessage(
            file_path=temp_file_15mb.name,
            file_name="test_file",
            mime_type=None,
            entity_type="span",
            entity_id=NOT_USED,
            project_name=NOT_USED,
            encoded_url_override=NOT_USED,
        )

        tested.put(attachment)
        tested.put(attachment)

        assert file_upload_manager.remaining_data().uploads == 2

        # we have timeout less than upload time 1 < 2, thus not all uploads will be completed
        assert tested.flush(timeout=1, upload_sleep_time=1) is False

        assert file_upload_manager.remaining_data().uploads == 2
