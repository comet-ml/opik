import abc
import logging
from typing import List, Optional, TypeVar, Type

from . import messages

import opik.exceptions


LOGGER = logging.getLogger(__name__)

T = TypeVar("T")


class BaseMessageProcessor(abc.ABC):
    @abc.abstractmethod
    def process(self, message: messages.BaseMessage) -> None:
        pass

    @abc.abstractmethod
    def is_active(self) -> bool:
        return False


class ChainedMessageProcessor(BaseMessageProcessor):
    """
    Processes messages through a chain of message processors.

    This class allows for the sequential processing of a message by a list of
    `BaseMessageProcessor` instances. Each processor in the chain is invoked in the
    order provided. If an exception occurs during the processing by a specific
    processor, it is logged, and the process continues with the next processor in
    the chain.
    """

    def __init__(self, processors: List[BaseMessageProcessor]) -> None:
        self._processors = processors

    def is_active(self) -> bool:
        return True

    def process(self, message: messages.BaseMessage) -> None:
        rate_limit_error: Optional[opik.exceptions.OpikCloudRequestsRateLimited] = None

        for processor in self._processors:
            try:
                processor.process(message)
            except opik.exceptions.OpikCloudRequestsRateLimited as ex:
                rate_limit_error = ex
            except Exception as ex:
                LOGGER.error(
                    "Unexpected error while processing message: %s with message processor: %s",
                    ex,
                    type(processor),
                    exc_info=True,
                )

        # Rate limit error is a special case that is handled by the caller.
        if rate_limit_error is not None:
            raise rate_limit_error

    def get_processor_by_type(self, processor_type: Type[T]) -> Optional[T]:
        """
        Retrieves a processor from the available processors that matches the specified type.

        This method iterates through the list of processors and checks if any of them is
        an instance of the given class type. If a match is found, it returns the processor.

        Args:
            processor_type: Concrete processor class to search for.

        Returns:
            The processor matching the specified type if found, else None.
        """
        for processor in self._processors:
            if isinstance(processor, processor_type):
                return processor
        return None
