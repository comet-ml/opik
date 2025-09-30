from typing import Any, Union, List, Dict
from collections.abc import Callable

import copy

from pydantic import BaseModel, Field

from opik import track

# Type alias for multimodal content support
MessageContent = Union[str, List[Dict[str, Any]]]


class Tool(BaseModel):
    name: str = Field(..., description="Name of the tool")
    description: str = Field(..., description="Description of the tool")
    parameters: dict[str, Any] = Field(
        ..., description="JSON Schema defining the input parameters for the tool"
    )


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
        messages: list[dict[str, MessageContent]] | None = None,
        tools: list[dict[str, Any]] | None = None,
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

        if messages is not None:
            if not isinstance(messages, list):
                raise ValueError("`messages` must be a list")
            else:
                for message in messages:
                    if not isinstance(message, dict):
                        raise ValueError("`messages` must be a dictionary")
                    elif "role" not in message or "content" not in message:
                        raise ValueError(
                            "`message` must have 'role' and 'content' keys."
                        )
                    # Validate structured content format if present
                    content = message.get("content")
                    if isinstance(content, list):
                        self._validate_structured_content(content)
        self.name = name
        self.system = system
        self.user = user
        self.messages = messages
        # ALl of the rest are just for the ChatPrompt LLM
        # These are used from the prompt as controls:
        self.tools = tools
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

    def _validate_structured_content(self, content: list) -> None:
        """Validate OpenAI-style structured content format."""
        for part in content:
            if not isinstance(part, dict):
                raise ValueError("Structured content parts must be dictionaries")

            part_type = part.get("type")
            if part_type not in ("text", "image_url"):
                raise ValueError(f"Invalid content type: {part_type}. Must be 'text' or 'image_url'")

            if part_type == "text" and "text" not in part:
                raise ValueError("Text part must have 'text' field")

            if part_type == "image_url" and "image_url" not in part:
                raise ValueError("Image part must have 'image_url' field")

    def _substitute_structured_content(
        self,
        content: list[dict[str, Any]],
        key: str,
        value: Any
    ) -> list[dict[str, Any]]:
        """
        Substitute template variables in structured content.

        Handles:
        - Text parts: Replace {key} with value
        - Image URL parts: Replace {key} with value (for base64 data URIs)
        """
        label = "{" + key + "}"
        result = []

        for part in content:
            part_copy = part.copy()

            if part["type"] == "text":
                text = part["text"]
                if label in text:
                    part_copy["text"] = text.replace(label, str(value))

            elif part["type"] == "image_url":
                url = part["image_url"]["url"]
                if label in url:
                    part_copy["image_url"] = part["image_url"].copy()
                    part_copy["image_url"]["url"] = url.replace(label, str(value))

            result.append(part_copy)

        return result

    def get_messages(
        self,
        dataset_item: dict[str, Any] | None = None,
    ) -> list[dict[str, MessageContent]]:
        """
        Get messages with template variables substituted.

        Now handles both string and structured content.
        """
        # This is a copy, so we can alter the messages:
        messages = self._standardize_prompts()

        if dataset_item:
            for key, value in dataset_item.items():
                for message in messages:
                    content = message["content"]

                    # String content (existing behavior)
                    if isinstance(content, str):
                        label = "{" + key + "}"
                        if label in content:
                            message["content"] = content.replace(label, str(value))

                    # Structured content (NEW)
                    elif isinstance(content, list):
                        message["content"] = self._substitute_structured_content(
                            content, key, value
                        )

        return messages

    def _standardize_prompts(self, **kwargs: Any) -> list[dict[str, MessageContent]]:
        standardize_messages: list[dict[str, MessageContent]] = []

        if self.system is not None:
            standardize_messages.append({"role": "system", "content": self.system})

        if self.messages is not None:
            for message in self.messages:
                standardize_messages.append(message)

        if self.user is not None:
            standardize_messages.append({"role": "user", "content": self.user})

        return copy.deepcopy(standardize_messages)

    def to_dict(self) -> dict[str, str | list[dict[str, MessageContent]]]:
        """Convert ChatPrompt to a dictionary for JSON serialization.

        Returns:
            Dict containing the serializable representation of this ChatPrompt
        """
        retval: dict[str, str | list[dict[str, MessageContent]]] = {}
        if self.system is not None:
            retval["system"] = self.system
        if self.user is not None:
            retval["user"] = self.user
        if self.messages is not None:
            retval["messages"] = self.messages
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

    def set_messages(self, messages: list[dict[str, MessageContent]]) -> None:
        self.system = None
        self.user = None
        self.messages = copy.deepcopy(messages)

    # TODO(opik): remove this stop-gap once MetaPromptOptimizer supports MCP.
    # Provides a second-pass flow so tool results can be appended before
    # rerunning the model.
    def with_messages(self, messages: list[dict[str, MessageContent]]) -> "ChatPrompt":
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
            system=obj.get("system", None),
            user=obj.get("user", None),
            messages=obj.get("messages", None),
        )
