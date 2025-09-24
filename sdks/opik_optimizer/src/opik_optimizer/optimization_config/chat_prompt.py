from typing import Any
from collections.abc import Callable

import copy

from pydantic import BaseModel, Field

from opik import track


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
        messages: list[dict[str, str]] | None = None,
        tools: list[dict[str, Any]] | None = None,
        function_map: dict[str, Callable] | None = None,
        model: str | None = None,
        invoke: Callable | None = None,
        project_name: str | None = "Default Project",
        **model_kwargs: Any,
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
        self.model_kwargs = model_kwargs
        self.invoke = invoke
        self.project_name = project_name

    def get_messages(
        self,
        dataset_item: dict[str, str] | None = None,
    ) -> list[dict[str, str]]:
        # This is a copy, so we can alter the messages:
        messages = self._standardize_prompts()

        if dataset_item:
            for key, value in dataset_item.items():
                for message in messages:
                    # Only replace user message content:
                    label = "{" + key + "}"
                    if label in message["content"]:
                        message["content"] = message["content"].replace(
                            label, str(value)
                        )
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
        model_kwargs = (
            copy.deepcopy(self.model_kwargs) if self.model_kwargs is not None else {}
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
            project_name=self.project_name,
            **model_kwargs,
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
            prompt=obj.get("prompt", None),
            messages=obj.get("messages", None),
        )
