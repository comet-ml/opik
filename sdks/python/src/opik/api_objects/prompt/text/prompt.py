import copy
import json
import logging
from typing import Any, Dict, Optional, Union, List
from typing_extensions import override
from opik.rest_api import types as rest_api_types
from . import prompt_template
from .. import types as prompt_types
from .. import client as prompt_client
from .. import base_prompt

LOGGER = logging.getLogger(__name__)


class Prompt(base_prompt.BasePrompt):
    """
    Prompt class represents a prompt with a name, prompt text/template and commit hash.
    """

    def __init__(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        validate_placeholders: bool = True,
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        """
        Initializes a new instance of the class with the given parameters.
        Creates a new text prompt using the opik client and sets the initial state of the instance attributes based on the created prompt.

        Parameters:
            name: The name for the prompt.
            prompt: The template for the prompt.
            metadata: Optional metadata for the prompt.
            type: The template type (MUSTACHE or JINJA2).
            validate_placeholders: Whether to validate template placeholders.
            id: Optional unique identifier (UUID) for the prompt.
            description: Optional description of the prompt (up to 255 characters).
            change_description: Optional description of changes in this version.
            tags: Optional list of tags to associate with the prompt.

        Raises:
            PromptTemplateStructureMismatch: If a chat prompt with the same name already exists (template structure is immutable).
        """

        self._template = prompt_template.PromptTemplate(
            template=prompt, type=type, validate_placeholders=validate_placeholders
        )
        self._name = name
        self._metadata = metadata
        self._type = type
        self._id = id
        self._description = description
        self._change_description = change_description
        self._tags = copy.copy(tags) if tags else []

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
            id=self._id,
            description=self._description,
            change_description=self._change_description,
            tags=self._tags,
        )

        self._commit = prompt_version.commit
        self.__internal_api__prompt_id__ = prompt_version.prompt_id
        self.__internal_api__version_id__ = prompt_version.id
        # Update fields from backend response to ensure consistency
        self._id = prompt_version.id
        self._change_description = prompt_version.change_description
        self._tags = prompt_version.tags

    @property
    @override
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def prompt(self) -> str:
        """The latest template of the prompt."""
        return str(self._template)

    @property
    @override
    def commit(self) -> Optional[str]:
        """The commit hash of the prompt."""
        return self._commit

    @property
    @override
    def version_id(self) -> str:
        """The unique identifier of the prompt version."""
        return self.__internal_api__version_id__

    @property
    @override
    def metadata(self) -> Optional[Dict[str, Any]]:
        """The metadata dictionary associated with the prompt"""
        return copy.deepcopy(self._metadata)

    @property
    @override
    def type(self) -> prompt_types.PromptType:
        """The prompt type of the prompt."""
        return self._type

    @property
    @override
    def id(self) -> Optional[str]:
        """The unique identifier (UUID) of the prompt."""
        return self._id

    @property
    @override
    def description(self) -> Optional[str]:
        """The description of the prompt."""
        return self._description

    @property
    @override
    def change_description(self) -> Optional[str]:
        """The description of changes in this version."""
        return self._change_description

    @property
    @override
    def tags(self) -> Optional[List[str]]:
        """The list of tags associated with the prompt."""
        return copy.copy(self._tags) if self._tags else []

    @override
    def format(self, **kwargs: Any) -> Union[str, List[Dict[str, Any]]]:
        """
        Replaces placeholders in the template with provided keyword arguments.

        Args:
            **kwargs: Arbitrary keyword arguments where the key represents the placeholder
                      in the template and the value is the value to replace the placeholder with.

        Returns:
            A string with all placeholders replaced by their corresponding values from kwargs.
        """
        is_playground_chat_prompt = (
            self._metadata is not None
            and self._metadata.get("created_from") == "opik_ui"
            and self._metadata.get("type") == "messages_json"
        )
        formatted_string = self._template.format(**kwargs)

        if is_playground_chat_prompt:
            try:
                return json.loads(formatted_string)
            except json.JSONDecodeError:
                LOGGER.error(
                    f"Failed to parse JSON string: {formatted_string}. Make sure chat prompt is valid JSON. Returning the raw string."
                )
                return formatted_string

        return formatted_string

    @override
    def __internal_api__to_info_dict__(self) -> Dict[str, Any]:
        """
        Convert the prompt to an info dictionary for serialization.

        Returns:
            Dictionary containing prompt metadata and version information.
        """
        info_dict: Dict[str, Any] = {
            "name": self.name,
            "template_structure": "text",
            "version": {
                "template": self.prompt,
            },
        }

        if self.__internal_api__prompt_id__ is not None:
            info_dict["id"] = self.__internal_api__prompt_id__

        if self.commit is not None:
            info_dict["version"]["commit"] = self.commit

        if self.__internal_api__version_id__ is not None:
            info_dict["version"]["id"] = self.__internal_api__version_id__

        if self._metadata is not None:
            info_dict["version"]["metadata"] = self._metadata

        return info_dict

    @classmethod
    def from_fern_prompt_version(
        cls,
        name: str,
        prompt_version: rest_api_types.PromptVersionDetail,
    ) -> "Prompt":
        # will not call __init__ to avoid API calls, create new instance with __new__
        prompt = cls.__new__(cls)

        prompt.__internal_api__version_id__ = prompt_version.id
        prompt.__internal_api__prompt_id__ = prompt_version.prompt_id

        prompt._name = name
        prompt._template = prompt_template.PromptTemplate(
            template=prompt_version.template,
            type=prompt_types.PromptType(prompt_version.type)
            or prompt_types.PromptType.MUSTACHE,
        )
        prompt._commit = prompt_version.commit
        prompt._metadata = prompt_version.metadata
        prompt._type = prompt_version.type
        prompt._id = prompt_version.id
        prompt._description = (
            None  # description is stored at prompt level, not version level
        )
        prompt._change_description = prompt_version.change_description
        prompt._tags = copy.copy(prompt_version.tags) if prompt_version.tags else []
        return prompt
