import copy
import json
from collections.abc import Callable
from typing import Any

from opik import track

from . import types


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

    DISPLAY_TRUNCATION_LENGTH = 500

    def __init__(
        self,
        name: str = "chat-prompt",
        system: str | None = None,
        user: str | None = None,
        messages: list[dict[str, Any]] | None = None,
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
            self._validate_messages(messages)

        if tools is not None:
            self._validate_tools(tools)

        self.name = name

        self.system = system
        self.user = user
        self.messages = messages

        self.tools = tools
        if function_map is not None:
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
    def _content_part_has_payload(part: Any) -> bool:
        """Return True when a multimodal part contains non-empty info."""
        if isinstance(part, types.TextContentPart):
            return bool(part.text.strip())

        if isinstance(part, types.ImageContentPart):
            return bool(part.image_url.url.strip())

        if isinstance(part, dict):
            part_type = part.get("type")
            if part_type == "text":
                text = part.get("text")
                if isinstance(text, str) and text.strip():
                    return True
            elif part_type == "image_url":
                image_url = part.get("image_url", {})
                if isinstance(image_url, dict):
                    url = image_url.get("url")
                    if isinstance(url, str) and url.strip():
                        return True

            # Fallback: check any nested strings (future modalities or custom schemas).
            for value in part.values():
                if isinstance(value, str) and value.strip():
                    return True
                if isinstance(value, dict) and ChatPrompt._content_part_has_payload(
                    value
                ):
                    return True

        return False

    @classmethod
    def _has_non_empty_content(cls, content: types.Content) -> bool:
        """Return True if the message content carries at least one non-empty value."""
        if isinstance(content, str):
            return bool(content.strip())

        if isinstance(content, list):
            if not content:
                return False
            for part in content:
                if cls._content_part_has_payload(part):
                    return True
            return False

        return False

    @classmethod
    def _validate_messages(cls, messages: list[dict[str, Any]]) -> None:
        if not isinstance(messages, list):
            raise ValueError("`messages` must be a list")

        for idx, message in enumerate(messages):
            validated_message = types.Message.model_validate(message)
            if not cls._has_non_empty_content(validated_message.content):
                raise ValueError(
                    f"Message at index {idx} with role '{validated_message.role}' must include non-empty content."
                )

    @staticmethod
    def _validate_tools(tools: list[dict[str, Any]]) -> None:
        if not isinstance(tools, list):
            raise ValueError("`tools` must be a list")
        else:
            for tool in tools:
                types.Tool.model_validate(tool)

    @staticmethod
    def _merge_messages(
        system: str | None, user: str | None, messages: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        merged_messages = []
        if system is not None:
            merged_messages.append({"role": "system", "content": system})
        if user is not None:
            merged_messages.append({"role": "user", "content": user})
        if messages is not None:
            merged_messages.extend(messages)
        return merged_messages

    def _has_content_parts(self) -> bool:
        messages = self._standardize_prompts()

        for message in messages:
            if isinstance(message["content"], list):
                return True
        return False

    @staticmethod
    def _update_string_content(content: str, label: str, value: str) -> str:
        """
        Update string content by replacing label with value.

        Args:
            content: String content to update
            label: Label to replace (e.g., "{question}")
            value: Value to replace label with

        Returns:
            Updated string content
        """
        if label in content:
            return content.replace(label, value)
        return content

    @staticmethod
    def _update_content_parts(
        content_parts: list[dict[str, Any]], label: str, value: str
    ) -> None:
        """
        Update content parts (multimodal) by replacing label with value in text and image_url parts.

        Args:
            content_parts: List of content part dictionaries
            label: Label to replace (e.g., "{question}")
            value: Value to replace label with
        """
        for part in content_parts:
            part_type = part.get("type")

            # Replace in text parts
            if part_type == "text":
                text_content = part.get("text", "")
                if isinstance(text_content, str) and label in text_content:
                    part["text"] = text_content.replace(label, value)

            # Replace in image_url parts
            elif part_type == "image_url":
                image_url_data = part.get("image_url", {})
                if isinstance(image_url_data, dict):
                    url = image_url_data.get("url", "")
                    if isinstance(url, str) and label in url:
                        image_url_data["url"] = url.replace(label, value)

    def get_messages(
        self,
        dataset_item: dict[str, str] | None = None,
    ) -> list[dict[str, Any]]:
        # This is a copy, so we can alter the messages:
        messages = self._standardize_prompts()

        if dataset_item:
            for key, value in dataset_item.items():
                for message in messages:
                    # Only replace user message content:
                    label = "{" + key + "}"
                    content = message["content"]

                    # Handle string content
                    if isinstance(content, str):
                        message["content"] = self._update_string_content(
                            content, label, str(value)
                        )

                    # Handle list of content parts (multimodal)
                    elif isinstance(content, list):
                        self._update_content_parts(content, label, str(value))
        return messages

    def _standardize_prompts(self, **kwargs: Any) -> list[dict[str, str]]:
        standardize_messages: list[dict[str, str]] = []

        if self.system is not None:
            standardize_messages.append({"role": "system", "content": self.system})

        if self.messages is not None:
            for message in self.messages:
                standardize_messages.append(message)

        if self.user is not None:
            standardize_messages.append({"role": "user", "content": self.user})

        return copy.deepcopy(standardize_messages)

    def _format_messages_for_display(self) -> str:
        """
        Serialize messages for logging/printing while avoiding large payloads.
        """
        messages = self._standardize_prompts()
        try:
            serialized = json.dumps(messages, ensure_ascii=False)
        except (TypeError, ValueError):
            serialized = str(messages)

        if len(serialized) > self.DISPLAY_TRUNCATION_LENGTH:
            return serialized[: self.DISPLAY_TRUNCATION_LENGTH] + "..."
        return serialized

    def __str__(self) -> str:
        return self._format_messages_for_display()

    def __repr__(self) -> str:
        return f"ChatPrompt(name={self.name!r}, messages={self._format_messages_for_display()!r})"

    def to_dict(self) -> dict[str, str | list[dict[str, str]]]:
        """Convert ChatPrompt to a dictionary for JSON serialization.

        Returns:
            Dict containing the serializable representation of this ChatPrompt
        """
        retval: dict[str, str | list[dict[str, str]]] = {}
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

    def set_messages(self, messages: list[dict[str, Any]]) -> None:
        self.system = None
        self.user = None
        self.messages = copy.deepcopy(messages)

    # TODO(opik): remove this stop-gap once MetaPromptOptimizer supports MCP.
    # Provides a second-pass flow so tool results can be appended before
    # rerunning the model.
    def with_messages(self, messages: list[dict[str, Any]]) -> "ChatPrompt":
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
