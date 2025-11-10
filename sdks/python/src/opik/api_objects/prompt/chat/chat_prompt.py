import copy
import json
from typing import Any, Dict, List, Optional
from typing_extensions import override

from opik.rest_api import types as rest_api_types
from . import chat_prompt_template
from .. import client as prompt_client
from .. import types as prompt_types
from .. import base_prompt


class ChatPrompt(base_prompt.BasePrompt):
    """
    ChatPrompt class represents a chat-style prompt with a name, message array template and commit hash.
    Similar to Prompt but uses a list of chat messages instead of a string template.
    """

    def __init__(
        self,
        name: str,
        messages: List[Dict[str, prompt_types.MessageContent]],
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        validate_placeholders: bool = True,
    ) -> None:
        """
        Initializes a new instance of the ChatPrompt class.
        Creates a new chat prompt using the opik client and sets the initial state.

        Parameters:
            name: The name for the prompt.
            messages: List of message dictionaries with 'role' and 'content' fields.
            metadata: Optional metadata to be included in the prompt.
            type: The template type (MUSTACHE or JINJA2).
        """

        self._chat_template = chat_prompt_template.ChatPromptTemplate(
            messages=messages,
            template_type=type,
            validate_placeholders=validate_placeholders,
        )
        self._name = name
        self._metadata = metadata
        self._type = type
        self._messages = messages
        self._commit: Optional[str] = None
        self.__internal_api__prompt_id__: str
        self.__internal_api__version_id__: str

        self._sync_with_backend()

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
        )

        self._commit = prompt_version.commit
        self.__internal_api__prompt_id__ = prompt_version.prompt_id
        self.__internal_api__version_id__ = prompt_version.id

    @property
    @override
    def name(self) -> str:
        """The name of the prompt."""
        return self._name

    @property
    def messages(self) -> List[Dict[str, prompt_types.MessageContent]]:
        """The chat messages template."""
        return copy.deepcopy(self._messages)

    @property
    @override
    def commit(self) -> Optional[str]:
        """The commit hash of the prompt."""
        return self._commit

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

    @override
    def format(
        self,
        variables: Dict[str, Any],
    ) -> List[Dict[str, prompt_types.MessageContent]]:
        """
        Renders the chat template with provided variables.

        Args:
            variables: Dictionary of variables to substitute in the template.

        Returns:
            A list of rendered message dictionaries.
        """
        return self._chat_template.format(variables=variables)

    @override
    def __internal_api__to_info_dict__(self) -> Dict[str, Any]:
        """
        Convert the prompt to an info dictionary for serialization.

        Returns:
            Dictionary containing prompt metadata and version information.
        """
        info_dict: Dict[str, Any] = {
            "name": self.name,
            "version": {
                "messages": self.messages,
            },
        }

        if self.__internal_api__prompt_id__ is not None:
            info_dict["id"] = self.__internal_api__prompt_id__

        if self.commit is not None:
            info_dict["version"]["commit"] = self.commit

        if self.__internal_api__version_id__ is not None:
            info_dict["version"]["id"] = self.__internal_api__version_id__

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
        return chat_prompt
