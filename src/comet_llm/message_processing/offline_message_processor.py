from .messages import BaseMessage
from .offline_senders import prompt, chain

class OfflineMessageProcessor:
    def __init__(self, offline_directory: str, batch_duration_seconds: int) -> None:
        self._offline_directory = offline_directory
        self._batch_duration_seconds = batch_duration_seconds

    def process(message: BaseMessage) -> None:
        pass