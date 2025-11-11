from typing import Any, cast, TypeAlias
from collections.abc import Callable

import copy

from pydantic import BaseModel, Field

from opik import track
from opik_optimizer.utils.multimodal import (
    replace_label_in_multimodal_content,
    validate_structured_content_parts,
)


class Tool(BaseModel):
    name: str = Field(..., description="Name of the tool")
    description: str = Field(..., description="Description of the tool")
    parameters: dict[str, Any] = Field(
        ..., description="JSON Schema defining the input parameters for the tool"
    )


ToolDict: TypeAlias = dict[str, Any]
TextPart: TypeAlias = dict[str, Any]
MediaPart: TypeAlias = dict[str, Any]
MessageDict: TypeAlias = dict[str, Any]


class ChatPrompt:
    """
    The ChatPrompt lies at the core of Opik Optimizer. It is
    either a series of messages, or a system and/or prompt.

    The ChatPrompt must make reference to at least one field
    in the associated database when used with optimizations.

    Args:
        system: the system prompt
        prompt: contains {input-dataset-field}, if given
        messages: a list of dictionaries with role/content, with
            a content containing {input-dataset-field}
    """

    def __init__(
        self,
        name: str = "chat-prompt",
        system: str | None = None,
        user: str | None = None,
        messages: list[dict[str, Any]] | None = None,
        tools: list[ToolDict] | None = None,
        function_map: dict[str, Callable] | None = None,
        model: str = "gpt-4o-mini",
        invoke: Callable | None = None,
        model_parameters: dict[str, Any] | None = None,
    ) -> None:
        if system is None and user is None and messages is None:
            raise ValueError(
                "At least one of `system`, `user`, or `messages` must be provided"
            )

        if user is not None and messages is not None:
            raise ValueError("`user` and `messages` cannot be provided together")

        if system is not None and messages is not None:
            raise ValueError("`system` and `messages` cannot be provided together")

        if system is not None and not isinstance(system, str):
            raise ValueError("`system` must be a string")

        if user is not None and not isinstance(user, str):
            raise ValueError("`user` must be a string")

        self.name = name
        self.system = system
        self.user = user
        self.messages = (
            self.validate_messages(messages) if messages is not None else None
        )
        # All of the rest are just for the ChatPrompt LLM
        # These are used from the prompt as controls:
        self.tools: list[ToolDict] | None = tools
        if function_map:
            self.function_map = {
                key: (
                    value
                    if hasattr(value, "__wrapped__")
                    else track(type="tool")(value)
                )
                for key, value in function_map.items()
            }
        else:
            self.function_map = {}
        # These are used for the LiteLLMAgent class:
        self.model = model
        self.model_kwargs = model_parameters or {}
        self.invoke = invoke

    @staticmethod
    def validate_messages(messages: list[MessageDict]) -> list[MessageDict]:
        """Validate and normalize messages to MessageDict format."""
        if not isinstance(messages, list):
            raise ValueError("`messages` must be a list")

        normalised: list[MessageDict] = []
        for message in messages:
            ChatPrompt._validate_message_dict(message)
            normalised.append(cast(MessageDict, message))

        return normalised

    @staticmethod
    def _validate_message_dict(message: MessageDict) -> None:
        if not isinstance(message, dict):
            raise ValueError("Each item in `messages` must be a dictionary")

        if "role" not in message or "content" not in message:
            raise ValueError("Each message must include 'role' and 'content'")

        content = message["content"]
        if isinstance(content, list):
            validate_structured_content_parts(content)

    def get_messages(
        self,
        dataset_item: dict[str, Any] | None = None,
    ) -> list[MessageDict]:
        # This is a copy, so we can alter the messages:
        messages = self._standardize_prompts()

        if not dataset_item:
            return messages

        for key, value in dataset_item.items():
            label = "{" + str(key) + "}"
            for i, message in enumerate(messages):
                messages[i] = self._replace_in_message(message, label, value)
        return messages

    @staticmethod
    def _replace_in_message(
        message: MessageDict, label: str, replacement_value: Any
    ) -> MessageDict:
        content = message["content"]

        if (
            isinstance(content, str)
            and isinstance(replacement_value, list)
            and content.strip() == label
        ):
            ChatPrompt.validate_messages(
                [{"role": message["role"], "content": replacement_value}]
            )
            return cast(
                MessageDict,
                {
                    "role": message["role"],
                    "content": copy.deepcopy(replacement_value),
                },
            )

        replacement = str(replacement_value)
        new_content = ChatPrompt._replace_in_content(content, label, replacement)

        return cast(
            MessageDict,
            {
                "role": message["role"],
                "content": new_content,
            },
        )

    @staticmethod
    def _replace_in_content(
        content: str | list[TextPart | MediaPart], label: str, replacement: str
    ) -> str | list[TextPart | MediaPart]:
        if isinstance(content, str):
            return content.replace(label, replacement)
        if isinstance(content, list):
            replaced = replace_label_in_multimodal_content(
                cast(list[dict[str, Any]], content), label, replacement
            )
            return cast(list[TextPart | MediaPart], replaced)
        return content

    def _standardize_prompts(self, **kwargs: Any) -> list[MessageDict]:
        standardize_messages: list[MessageDict] = []

        if self.system is not None:
            standardize_messages.append(
                cast(MessageDict, {"role": "system", "content": self.system})
            )

        if self.messages is not None:
            for message in self.messages:
                standardize_messages.append(cast(MessageDict, message))

        if self.user is not None:
            standardize_messages.append(
                cast(MessageDict, {"role": "user", "content": self.user})
            )

        return copy.deepcopy(standardize_messages)

    def to_dict(self) -> dict[str, Any]:
        """Convert ChatPrompt to a dictionary for JSON serialization.

        Handles nested structures (e.g., in tools or external data) that may
        contain Pydantic models, converting them to plain dicts.

        Returns:
            Dict containing the serializable representation of this ChatPrompt
        """

        def _to_plain(obj: Any) -> Any:
            if isinstance(obj, BaseModel):
                return obj.model_dump()
            if isinstance(obj, list):
                return [_to_plain(item) for item in obj]
            if isinstance(obj, dict):
                return {k: _to_plain(v) for k, v in obj.items()}
            return obj

        retval: dict[str, Any] = {}
        if self.system is not None:
            retval["system"] = _to_plain(self.system)
        if self.user is not None:
            retval["user"] = _to_plain(self.user)
        if self.messages is not None:
            retval["messages"] = _to_plain(self.messages)
        return retval

    def copy(self) -> "ChatPrompt":
        """Shallow clone preserving model configuration and tools."""

        # TODO(opik-mcp): once we introduce a dedicated MCP prompt subclass,
        # migrate callers away from generic copies so optimizer metadata stays typed.
        model_parameters = (
            copy.deepcopy(self.model_kwargs) if self.model_kwargs else None
        )
        return ChatPrompt(
            name=self.name,
            system=self.system,
            user=self.user,
            messages=copy.deepcopy(self.messages),
            tools=copy.deepcopy(self.tools),
            function_map=self.function_map,
            model=self.model,
            invoke=self.invoke,
            model_parameters=model_parameters,
        )

    def set_messages(self, messages: list[MessageDict]) -> None:
        self.system = None
        self.user = None
        self.messages = copy.deepcopy(messages)

    # TODO(opik): remove this stop-gap once MetaPromptOptimizer supports MCP.
    # Provides a second-pass flow so tool results can be appended before
    # rerunning the model.
    def with_messages(self, messages: list[MessageDict]) -> "ChatPrompt":
        cloned = self.copy()
        cloned.set_messages(messages)
        return cloned

    @classmethod
    def model_validate(
        cls,
        obj: Any,
        *,
        strict: bool | None = None,
        from_attributes: bool | None = None,
        context: Any | None = None,
        by_alias: bool | None = None,
        by_name: bool | None = None,
    ) -> "ChatPrompt":
        """Custom validation method to handle nested objects during deserialization."""
        return ChatPrompt(
            name=obj.get("name", "chat-prompt"),
            system=obj.get("system", None),
            user=obj.get("user", None),
            messages=obj.get("messages", None),
            tools=obj.get("tools", None),
            model=obj.get("model", "gpt-4o-mini"),
            model_parameters=obj.get("model_parameters", None),
        )
