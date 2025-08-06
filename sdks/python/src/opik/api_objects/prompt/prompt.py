import copy
from typing import Any, Dict, Optional

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
        metadata: Optional[Dict[str, Any]] = None,
        type: PromptType = PromptType.MUSTACHE,
    ) -> None:
        """
        Initializes a new instance of the class with the given parameters.
        Creates a new prompt using the opik client and sets the initial state of the instance attributes based on the created prompt.

        Parameters:
            name: The name for the prompt.
            prompt: The template for the prompt.
        """

        self._template = PromptTemplate(template=prompt, type=type)
        self._name = name
        self._metadata = metadata
        self._type = type

        self._sync_with_backend()

    def _sync_with_backend(self) -> None:
        from opik.api_objects import opik_client

        opik_client_ = opik_client.get_client_cached()
        prompt_client_ = prompt_client.PromptClient(opik_client_.rest_client)
        prompt_version = prompt_client_.create_prompt(
            name=self._name,
            prompt=self._template.text,
            metadata=self._metadata,
            type=self._type,
        )

        self._commit = prompt_version.commit
        self.__internal_api__prompt_id__ = prompt_version.prompt_id
        self.__internal_api__version_id__ = prompt_version.id

    @property
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def prompt(self) -> str:
        """The latest template of the prompt."""
        return str(self._template)

    @property
    def commit(self) -> Optional[str]:
        """The commit hash of the prompt."""
        return self._commit

    @property
    def metadata(self) -> Optional[Dict[str, Any]]:
        """The metadata dictionary associated with the prompt"""
        return copy.deepcopy(self._metadata)

    @property
    def type(self) -> PromptType:
        """The prompt type of the prompt."""
        return self._type

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
        cls,
        name: str,
        prompt_version: PromptVersionDetail,
    ) -> "Prompt":
        # will not call __init__ to avoid API calls, create new instance with __new__
        prompt = cls.__new__(cls)

        prompt.__internal_api__version_id__ = prompt_version.id
        prompt.__internal_api__prompt_id__ = prompt_version.prompt_id

        prompt._name = name
        prompt._template = PromptTemplate(
            template=prompt_version.template,
            type=PromptType(prompt_version.type) or PromptType.MUSTACHE,
        )
        prompt._commit = prompt_version.commit
        prompt._metadata = prompt_version.metadata
        prompt._type = prompt_version.type
        return prompt
