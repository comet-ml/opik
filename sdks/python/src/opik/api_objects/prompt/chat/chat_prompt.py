import copy
import json
from typing import Any, Dict, List, Optional, Tuple, Type

from typing_extensions import override

from opik.rest_api import types as rest_api_types
from opik.validation import chat_prompt_messages, validator

from .. import base_prompt
from .. import client as prompt_client
from .. import types as prompt_types
from . import chat_prompt_template


class ChatPrompt(base_prompt.BasePrompt):
    """
    ChatPrompt class represents a chat-style prompt with a name, message array template and commit hash.
    Similar to Prompt but uses a list of chat messages instead of a string template.
    """

    _parameter_validators: List[Tuple[str, Type[validator.RaisableValidator]]] = [
        ("messages", chat_prompt_messages.ChatPromptMessagesValidator),
    ]

    def __init__(
        self,
        name: str,
        messages: List[Dict[str, prompt_types.MessageContent]],
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        validate_placeholders: bool = False,
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        """
        Initializes a new instance of the ChatPrompt class.
        Creates a new chat prompt using the opik client and sets the initial state.

        Parameters:
            name: The name for the prompt.
            messages: List of message dictionaries with 'role' and 'content' fields.
            metadata: Optional metadata to be included in the prompt.
            type: The template type (MUSTACHE or JINJA2).
            validate_placeholders: Whether to validate template placeholders.
            id: Optional unique identifier (UUID) for the prompt.
            description: Optional description of the prompt (up to 255 characters).
            change_description: Optional description of changes in this version.
            tags: Optional list of tags to associate with the prompt.

        Raises:
            PromptTemplateStructureMismatch: If a text prompt with the same name already exists (template structure is immutable).
            ValidationError: If messages structure is invalid.
        """

        # Validate messages structure
        self._validate_inputs(messages=messages)

        self._chat_template = chat_prompt_template.ChatPromptTemplate(
            messages=messages,
            template_type=type,
            validate_placeholders=validate_placeholders,
        )
        self._name = name
        self._metadata = metadata
        self._type = type
        self._messages = messages
        self._id = id
        self._description = description
        self._change_description = change_description
        self._tags = copy.copy(tags) if tags else []
        self._commit: Optional[str] = None
        self.__internal_api__prompt_id__: str
        self.__internal_api__version_id__: str

        self._sync_with_backend()

    def _validate_inputs(self, **kwargs: Any) -> None:
        for parameter, validator_class in self._parameter_validators:
            if parameter in kwargs:
                validator_instance = validator_class(kwargs[parameter])
                validator_instance.validate()
                validator_instance.raise_if_validation_failed()

    def _sync_with_backend(self) -> None:
        from opik.api_objects import opik_client

        opik_client_ = opik_client.get_client_cached()
        prompt_client_ = prompt_client.PromptClient(opik_client_.rest_client)

        # Convert messages array to JSON string for backend storage
        messages_str = json.dumps(self._messages)

        prompt_version = prompt_client_.create_prompt(
            name=self._name,
            prompt=messages_str,
            metadata=self._metadata,
            type=self._type,
            template_structure="chat",
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
    def template(self) -> List[Dict[str, prompt_types.MessageContent]]:
        """The chat messages template."""
        return copy.deepcopy(self._messages)

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
    def id(self) -> Optional[str]:
        """The unique identifier (UUID) of the prompt."""
        return self._id

    @property
    def description(self) -> Optional[str]:
        """The description of the prompt."""
        return self._description

    @property
    def change_description(self) -> Optional[str]:
        """The description of changes in this version."""
        return self._change_description

    @property
    @override
    def tags(self) -> Optional[List[str]]:
        """The list of tags associated with the prompt."""
        return copy.copy(self._tags) if self._tags else []

    @override
    def format(
        self,
        variables: Dict[str, Any],
        supported_modalities: Optional[prompt_types.SupportedModalities] = None,
    ) -> List[Dict[str, prompt_types.MessageContent]]:
        """
        Renders the chat template with provided variables.

        Args:
            variables: Dictionary of variables to substitute in the template.
            supported_modalities: Optional dictionary specifying which modalities are supported
                by the target model. Keys are modality names ("vision" or "video") and values
                are booleans indicating support. When a modality is not supported (False or not
                specified), structured content parts (e.g., images, videos) are replaced with
                text placeholders like "<<<image>>>" or "<<<video>>>". When supported (True),
                the structured content is preserved as-is.
                Example: {"vision": True, "video": False}

                If not specified, all modalities default to SUPPORTED. Example: {"vision": True, "video": False}

        Returns:
            A list of rendered message dictionaries with variables substituted and multimodal
            content either preserved or replaced with placeholders based on supported_modalities.
        """
        if supported_modalities is None:
            supported_modalities = {
                "vision": True,
                "video": True,
            }

        return self._chat_template.format(
            variables=variables, supported_modalities=supported_modalities
        )

    @override
    def __internal_api__to_info_dict__(self) -> Dict[str, Any]:
        """
        Convert the prompt to an info dictionary for serialization.

        Returns:
            Dictionary containing prompt metadata and version information.
        """
        info_dict: Dict[str, Any] = {
            "name": self.name,
            "template_structure": "chat",
            "version": {
                "template": self.template,
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
    ) -> "ChatPrompt":
        # will not call __init__ to avoid API calls, create new instance with __new__
        chat_prompt = cls.__new__(cls)

        chat_prompt.__internal_api__version_id__ = prompt_version.id
        chat_prompt.__internal_api__prompt_id__ = prompt_version.prompt_id

        chat_prompt._name = name

        # Parse messages from JSON string
        messages = json.loads(prompt_version.template)
        chat_prompt._messages = messages
        chat_prompt._chat_template = chat_prompt_template.ChatPromptTemplate(
            messages=messages,
            template_type=prompt_types.PromptType(prompt_version.type)
            or prompt_types.PromptType.MUSTACHE,
        )
        chat_prompt._commit = prompt_version.commit
        chat_prompt._metadata = prompt_version.metadata
        chat_prompt._type = prompt_version.type
        chat_prompt._id = prompt_version.id
        chat_prompt._description = (
            None  # description is stored at prompt level, not version level
        )
        chat_prompt._change_description = prompt_version.change_description
        chat_prompt._tags = (
            copy.copy(prompt_version.tags) if prompt_version.tags else []
        )
        return chat_prompt
