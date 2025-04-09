from typing import Type, Dict
from .. import messages
from . import base_batcher
from . import flushing_thread


class BatchManager:
    def __init__(
        self,
        message_to_batcher_mapping: Dict[
            Type[messages.BaseMessage], base_batcher.BaseBatcher
        ],
    ) -> None:
        self._message_to_batcher_mapping = message_to_batcher_mapping
        self._flushing_thread = flushing_thread.FlushingThread(
            batchers=list(self._message_to_batcher_mapping.values())
        )

    def start(self) -> None:
        self._flushing_thread.start()

    def stop(self) -> None:
        self._flushing_thread.close()

    def message_supports_batching(self, message: messages.BaseMessage) -> bool:
        if hasattr(message, "supports_batching"):
            return message.supports_batching

        return message.__class__ in self._message_to_batcher_mapping

    def process_message(self, message: messages.BaseMessage) -> None:
        self._message_to_batcher_mapping[type(message)].add(message)

    def is_empty(self) -> bool:
        return all(
            [
                batcher.is_empty()
                for batcher in self._message_to_batcher_mapping.values()
            ]
        )

    def flush(self) -> None:
        for batcher in self._message_to_batcher_mapping.values():
            batcher.flush()
