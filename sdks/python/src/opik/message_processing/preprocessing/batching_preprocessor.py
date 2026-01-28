from . import preprocessor
from .. import messages
from ..batching import batch_manager


class BatchingPreprocessor(preprocessor.MessagePreprocessor):
    """
    Handles message batching during preprocessing.

    The BatchingPreprocessor class processes messages, enabling efficient message
    batching if a batching manager is provided. It supports starting, stopping,
    flushing, and checking the state of the batching manager, ensuring that
    messages are processed or delegated based on their batching capabilities.
    """

    def __init__(self, batching_manager: batch_manager.BatchManager | None) -> None:
        self._batch_manager = batching_manager

    def preprocess(
        self, message: messages.BaseMessage | None
    ) -> messages.BaseMessage | None:
        if message is None:
            # possibly already processed
            return None

        if (
            self._batch_manager is not None
            and self._batch_manager.message_supports_batching(message)
        ):
            self._batch_manager.process_message(message)
            return None

        return message

    def start(self) -> None:
        if self._batch_manager is not None:
            self._batch_manager.start()

    def stop(self) -> None:
        if self._batch_manager is not None:
            self._batch_manager.stop()

    def flush(self) -> None:
        if self._batch_manager is not None:
            self._batch_manager.flush()

    def is_empty(self) -> bool:
        if self._batch_manager is not None:
            return self._batch_manager.is_empty()

        return True
