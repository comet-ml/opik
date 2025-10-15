import abc

from . import messages


class BaseMessageProcessor(abc.ABC):
    @abc.abstractmethod
    def process(self, message: messages.BaseMessage) -> None:
        pass

    @abc.abstractmethod
    def is_active(self) -> bool:
        return False
