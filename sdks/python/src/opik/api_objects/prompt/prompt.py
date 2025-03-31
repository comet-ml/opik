import copy
from typing import Any, Dict, Optional

from opik.rest_api.types import PromptVersionDetail
from .prompt_template import PromptTemplate
from .types import PromptType


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
        # We import opik client here to avoid circular import issue.
        #
        # Prompt object creation via `Prompt.__init__` is handled via creating a temporary
        # instance with `client.get_prompt` (which uses `from_fern_prompt_version` classmethod
        # constructor under the hood) and then copying its attributes to the current class.
        #
        # It is done to allow prompt creation both via `Prompt()` and `client.get_prompt()`
        from opik.api_objects import opik_client

        client = opik_client.get_client_cached()

        new_instance = client.create_prompt(
            name=name,
            prompt=prompt,
            metadata=metadata,
            type=type,
        )

        # TODO: synchronize names? Template and prompt.
        # prompt is actually a prompt template.
        self._template = PromptTemplate(template=new_instance.prompt, type=type)
        self._name = new_instance.name
        self._commit = new_instance.commit
        self._metadata = new_instance.metadata
        self._type = new_instance.type

        self.__internal_api__prompt_id__: str = new_instance.__internal_api__prompt_id__
        self.__internal_api__version_id__: str = (
            new_instance.__internal_api__version_id__
        )

    @property
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def prompt(self) -> str:
        """The latest template of the prompt."""
        return str(self._template)

    @property
    def commit(self) -> str:
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
