import copy
import json
import logging
from collections.abc import Callable
from typing import Any
from pydantic import BaseModel, ConfigDict
from opik import track

from . import types
from .. import constants


logger = logging.getLogger(__name__)


class ModelParameters(BaseModel):
    """Wrapper for model parameters that allows arbitrary key-value pairs."""

    model_config = ConfigDict(extra="allow")


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

    # FIXME: Move to constants.py
    DISPLAY_TRUNCATION_LENGTH = 500

    def __init__(
        self,
        name: str = "chat-prompt",
        system: str | None = None,
        user: str | None = None,
        messages: list[dict[str, Any]] | None = None,
        tools: list[dict[str, Any]] | None = None,
        function_map: dict[str, Callable] | None = None,
        model: str = constants.DEFAULT_MODEL,
        model_parameters: dict[str, Any] | None = None,
        model_kwargs: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        if "model_kwdargs" in kwargs:
            if model_kwargs is not None:
                logger.warning(
                    "ChatPrompt received both model_kwargs and model_kwdargs; "
                    "ignoring model_kwdargs."
                )
            else:
                model_kwargs = kwargs["model_kwdargs"]
                logger.warning(
                    "ChatPrompt received model_kwdargs; remapping to model_parameters."
                )
            kwargs.pop("model_kwdargs", None)
        if kwargs:
            unexpected = ", ".join(sorted(kwargs.keys()))
            raise TypeError(f"Unexpected keyword argument(s): {unexpected}")

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
        if model_kwargs is not None:
            if model_parameters is not None:
                logger.warning(
                    "ChatPrompt received model_kwargs and model_parameters; "
                    "using model_parameters."
                )
            else:
                logger.warning(
                    "ChatPrompt received model_kwargs; remapping to model_parameters."
                )
                model_parameters = model_kwargs

        self.model_kwargs = model_parameters or {}

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
                    sanitized_url = image_url_data.get("url", "")
                    if (
                        isinstance(sanitized_url, str)
                        and sanitized_url.startswith("{data:image/")
                        and sanitized_url.endswith("}")
                    ):
                        # Some datasets pass data URIs wrapped in braces; strip them to keep URLs valid.
                        image_url_data["url"] = sanitized_url[1:-1]

    def replace_in_messages(
        self, messages: list[dict[str, Any]], label: str, value: str
    ) -> list[dict[str, Any]]:
        for message in messages:
            content = message["content"]
            if isinstance(content, str):
                message["content"] = self._update_string_content(
                    content, label, str(value)
                )
            elif isinstance(content, list):
                self._update_content_parts(content, label, str(value))

        return messages

    def _has_content_parts(self) -> bool:
        messages = self._standardize_prompts()
        return any(isinstance(message.get("content"), list) for message in messages)

    def get_messages(
        self,
        dataset_item: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        # This is a copy, so we can alter the messages:
        messages = self._standardize_prompts()

        if dataset_item:
            for key, value in dataset_item.items():
                # Only replace user message content:
                label = "{" + key + "}"
                messages = self.replace_in_messages(messages, label, str(value))
        return messages

    def _standardize_prompts(self, **kwargs: Any) -> list[dict[str, Any]]:
        standardize_messages: list[dict[str, Any]] = []

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

    def to_dict(self) -> dict[str, str | list[dict[str, Any]]]:
        """Convert ChatPrompt to a dictionary for JSON serialization.

        Returns:
            Dict containing the serializable representation of this ChatPrompt
        """
        retval: dict[str, str | list[dict[str, Any]]] = {}
        if self.system is not None:
            retval["system"] = self.system
        if self.user is not None:
            retval["user"] = self.user
        if self.messages is not None:
            retval["messages"] = self.messages
        return retval

    def copy(self) -> "ChatPrompt":
        """Shallow clone preserving model configuration and tools."""

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
            model_parameters=model_parameters,
        )

    def set_messages(self, messages: list[dict[str, Any]]) -> None:
        self.system = None
        self.user = None
        self.messages = copy.deepcopy(messages)

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
