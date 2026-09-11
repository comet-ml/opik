import abc
from typing import Optional

from opik.message_processing import messages


class MessagePreprocessor(abc.ABC):
    """
    Abstract base class for message preprocessing.

    This class provides a common interface for pre-processing messages, allowing
    derived classes to implement custom preprocessing logic tailored to specific
    requirements. Instances of this class cannot be created directly; it must be
    subclassed with the `preprocess` method implemented.
    """

    @abc.abstractmethod
    def preprocess(
        self, message: Optional[messages.BaseMessage]
    ) -> Optional[messages.BaseMessage]:
        """
        Processes and preprocesses the given message to prepare it for further operations.

        This is an abstract method and needs to be implemented in any concrete subclass. The
        preprocessing step is typically used for transformations or checks on the given input
        message before further processing.

        Args:
            message: The input message to be preprocessed. This can
                optionally be None.

        Returns:
            The processed message after preprocessing. Returns None if the input message is None
            or if a message was fully consumed here and no further processing is required.
        """
        pass
