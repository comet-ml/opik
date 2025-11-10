"""
Base classes for prompts and prompt templates.

Defines abstract interfaces that both string and chat variants must implement.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, Optional, Union


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
    def metadata(self) -> Optional[Dict[str, Any]]:
        """The metadata dictionary associated with the prompt."""
        pass

    @property
    @abstractmethod
    def type(self) -> Any:
        """The prompt type (e.g., MUSTACHE, JINJA2)."""
        pass

    @property
    @abstractmethod
    def template_structure(self) -> str:
        """The template structure of the prompt ('string' or 'chat')."""
        pass

    @abstractmethod
    def format(self, **kwargs: Any) -> Any:
        """
        Format the prompt with the provided variables.
        
        Returns:
            Formatted output. Type depends on the implementation:
            - Prompt returns str
            - ChatPrompt returns List[Dict[str, MessageContent]]
        """
        pass

