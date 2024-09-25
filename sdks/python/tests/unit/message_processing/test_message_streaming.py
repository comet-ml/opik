import pytest
import mock
from opik.message_processing import streamer_constructors


@pytest.fixture
def streamer_and_mock_message_processor():
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
        )

        yield tested, mock_message_processor
    finally:
        tested.close(timeout=5)


def test_streamer__happyflow(streamer_and_mock_message_processor):
    tested, mock_message_processor = streamer_and_mock_message_processor

    tested.put("message-1")
    tested.put("message-2")
    tested.flush(timeout=0.1)

    mock_message_processor.process.assert_has_calls(
        [mock.call("message-1"), mock.call("message-2")]
    )
