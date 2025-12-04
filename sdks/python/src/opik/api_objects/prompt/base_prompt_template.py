"""
Base class for prompt templates.

Defines abstract interface that both string and chat template variants must implement.
"""

from abc import ABC, abstractmethod
from typing import Any


class BasePromptTemplate(ABC):
    """
    Abstract base class for prompt templates (string and chat).

    All prompt template implementations must provide a format method
    that takes variables and returns formatted output.
    """

    @abstractmethod
    def format(self, *args: Any, **kwargs: Any) -> Any:
        """
        Format the template with the provided variables.

        Returns:
            Formatted output. Type depends on the implementation:
            - PromptTemplate returns str
            - ChatPromptTemplate returns List[Dict[str, MessageContent]]
        """
        pass
