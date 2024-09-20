import mock
from opik.message_processing.batching import batch_manager

def test_batch_manager__messages_processing_methods():

    integers_batcher = mock.Mock()
    strings_batcher = mock.Mock()
    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)

    assert tested.message_supports_batching("a-string")
    assert tested.message_supports_batching(42)
    assert not tested.message_supports_batching(float(42.5))
    
    tested.process_message(42)
    integers_batcher.add.assert_called_once_with(42)

    tested.process_message("a-string")
    strings_batcher.add.assert_called_once_with("a-string")


def test_batch_manager__all_batchers_are_empty__batch_manager_is_empty():

    integers_batcher = mock.Mock()
    integers_batcher.is_empty.return_value = True
    strings_batcher = mock.Mock()
    strings_batcher.is_empty.return_value = True

    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)

    assert tested.is_empty()
    strings_batcher.is_empty.assert_called_once()
    integers_batcher.is_empty.assert_called_once()


def test_batch_manager__at_least_one_batcher_is_not_empty__batch_manager_is_not_empty():

    integers_batcher = mock.Mock()
    integers_batcher.is_empty.return_value = True
    strings_batcher = mock.Mock()
    strings_batcher.is_empty.return_value = False

    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)

    assert not tested.is_empty()
    strings_batcher.is_empty.assert_called_once()
    integers_batcher.is_empty.assert_called_once()


def test_batch_manager__flush_is_called__all_batchers_are_flushed():
    integers_batcher = mock.Mock()
    strings_batcher = mock.Mock()

    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)
    tested.flush()
    integers_batcher.flush.assert_called_once()
    strings_batcher.flush.assert_called_once()
