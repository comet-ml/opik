import copy
from typing import Any, Dict, Optional
from enum import Enum

from opik.rest_api.types import PromptVersionDetail
from .prompt_template import PromptTemplate
from .types import PromptType
from opik.api_objects.prompt import client as prompt_client


class Prompt:
    """
    Prompt class represents a prompt with a name, prompt text/template and commit hash.
    """

    def __init__(
        self,
        name: str,
        prompt: str,
        type: PromptType,
        metadata: Optional[Dict[str, Any]] = None,
        commit: Optional[str] = None,
        id: Optional[str] = None,
        created_by: Optional[str] = None,
        version_count: Optional[int] = None,
        created_at: Optional[str] = None,
        last_updated_at: Optional[str] = None,
        last_updated_by: Optional[str] = None,
    ):
        self._id = id
        self._name = name
        self._prompt = prompt
        self._type = type
        self._metadata = metadata
        self._commit = commit
        self._created_by = created_by
        self._version_count = version_count
        self._created_at = created_at
        self._last_updated_at = last_updated_at
        self._last_updated_by = last_updated_by

    @property
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def id(self) -> str:
        """The ID of the prompt."""
        return self._id

    @property
    def prompt(self) -> str:
        """The template content of the prompt."""
        return self._prompt

    @property
    def type(self) -> PromptType:
        """The type of the prompt."""
        return self._type

    @property
    def commit(self) -> Optional[str]:
        """The commit hash of the prompt."""
        return self._commit

    @property
    def metadata(self) -> Optional[Dict[str, Any]]:
        """The metadata associated with the prompt."""
        return self._metadata

    @property
    def created_by(self) -> Optional[str]:
        """The user who created the prompt."""
        return self._created_by

    @property
    def version_count(self) -> Optional[int]:
        """The number of versions of this prompt."""
        return self._version_count

    @property
    def created_at(self) -> Optional[str]:
        """When the prompt was created."""
        return self._created_at

    @property
    def last_updated_at(self) -> Optional[str]:
        """When the prompt was last updated."""
        return self._last_updated_at

    @property
    def last_updated_by(self) -> Optional[str]:
        """The user who last updated the prompt."""
        return self._last_updated_by

    def format(self, **kwargs: Any) -> str:
        """
        Replaces placeholders in the template with provided keyword arguments.

        Args:
            **kwargs: Arbitrary keyword arguments where the key represents the placeholder
                      in the template and the value is the value to replace the placeholder with.

        Returns:
            A string with all placeholders replaced by their corresponding values from kwargs.
        """
        return self._template.format(**kwargs)

    @classmethod
    def from_fern_prompt_version(
        cls, name: str, prompt_version: PromptVersionDetail, id: Optional[str] = None,
        created_by: Optional[str] = None, version_count: Optional[int] = None, 
        created_at: Optional[str] = None, last_updated_at: Optional[str] = None, 
        last_updated_by: Optional[str] = None
    ) -> "Prompt":
        """
        Creates a Prompt object from a Fern-generated PromptVersionDetail.
        """
        return cls(
            name=name,
            prompt=prompt_version.template,
            type=PromptType(prompt_version.type),
            metadata=prompt_version.metadata,
            commit=prompt_version.commit,
            id=id,
            created_by=created_by,
            version_count=version_count,
            created_at=created_at,
            last_updated_at=last_updated_at,
            last_updated_by=last_updated_by,
        )

    def __str__(self) -> str:
        return str(self._prompt)
