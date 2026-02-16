"""
Base class for prompts.

Defines abstract interface that both string and chat prompt variants must implement.
"""

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from . import types as prompt_types


class BasePrompt(ABC):
    """
    Abstract base class for prompts (string and chat).

    All prompt implementations must provide common properties and methods
    for interacting with the backend API.
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """The name of the prompt."""
        pass

    @property
    @abstractmethod
    def commit(self) -> Optional[str]:
        """The commit hash of the prompt version."""
        pass

    @property
    @abstractmethod
    def version_id(self) -> str:
        """The unique identifier of the prompt version."""
        pass

    @property
    @abstractmethod
    def metadata(self) -> Optional[Dict[str, Any]]:
        """The metadata dictionary associated with the prompt."""
        pass

    @property
    @abstractmethod
    def type(self) -> prompt_types.PromptType:
        """The prompt type (MUSTACHE or JINJA2)."""
        pass

    @property
    @abstractmethod
    def id(self) -> Optional[str]:
        """The unique identifier (UUID) of the prompt."""
        pass

    @property
    @abstractmethod
    def description(self) -> Optional[str]:
        """The description of the prompt."""
        pass

    @property
    @abstractmethod
    def change_description(self) -> Optional[str]:
        """The description of changes in this version."""
        pass

    @property
    @abstractmethod
    def tags(self) -> Optional[List[str]]:
        """The list of tags associated with the prompt."""
        pass

    # Internal API fields for backend synchronization
    __internal_api__prompt_id__: str
    __internal_api__version_id__: str

    @abstractmethod
    def format(self, *args: Any, **kwargs: Any) -> Any:
        """
        Format the prompt with the provided variables.

        Returns:
            Formatted output. Type depends on the implementation:
            - Prompt returns str
            - ChatPrompt returns List[Dict[str, MessageContent]]
        """
        pass

    @abstractmethod
    def __internal_api__to_info_dict__(self) -> Dict[str, Any]:
        """
        Convert the prompt to an info dictionary for serialization.

        Returns:
            Dictionary containing prompt metadata and version information.
        """
        pass
