import abc
import logging

from . import messages


LOGGER = logging.getLogger(__name__)


class BaseMessageProcessor(abc.ABC):
    @abc.abstractmethod
    def process(self, message: messages.BaseMessage) -> None:
        pass

    @abc.abstractmethod
    def is_active(self) -> bool:
        return False


class ChainedMessageProcessor(BaseMessageProcessor):
    def __init__(self, processors: list[BaseMessageProcessor]) -> None:
        self._processors = processors

    def is_active(self) -> bool:
        return True

    def process(self, message: messages.BaseMessage) -> None:
        for processor in self._processors:
            try:
                processor.process(message)
            except Exception as ex:
                LOGGER.error(
                    "Unexpected error while processing message: %s with message processor: %s",
                    ex,
                    type(processor),
                    exc_info=True,
                )
                continue
