from typing import Any, Dict, List, Optional, Union, Callable

import copy

from pydantic import BaseModel, Field

from opik import track


class Tool(BaseModel):
    name: str = Field(..., description="Name of the tool")
    description: str = Field(..., description="Description of the tool")
    parameters: Dict[str, Any] = Field(
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
        system: Optional[str] = None,
        user: Optional[str] = None,
        messages: Optional[List[Dict[str, str]]] = None,
        tools: Optional[List[Dict[str, Any]]] = None,
        function_map: Optional[Dict[str, Callable]] = None,
        model: Optional[str] = None,
        invoke: Optional[Callable] = None,
        project_name: Optional[str] = "Default Project",
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
        dataset_item: Optional[Dict[str, str]] = None,
    ) -> List[Dict[str, str]]:
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

    def _standardize_prompts(self, **kwargs: Any) -> List[Dict[str, str]]:
        standardize_messages: List[Dict[str, str]] = []

        if self.system is not None:
            standardize_messages.append({"role": "system", "content": self.system})

        if self.messages is not None:
            for message in self.messages:
                standardize_messages.append(message)

        if self.user is not None:
            standardize_messages.append({"role": "user", "content": self.user})

        return copy.deepcopy(standardize_messages)

    def to_dict(self) -> Dict[str, Union[str, List[Dict[str, str]]]]:
        """Convert ChatPrompt to a dictionary for JSON serialization.

        Returns:
            Dict containing the serializable representation of this ChatPrompt
        """
        retval: Dict[str, Union[str, List[Dict[str, str]]]] = {}
        if self.system is not None:
            retval["system"] = self.system
        if self.user is not None:
            retval["user"] = self.user
        if self.messages is not None:
            retval["messages"] = self.messages
        return retval

    def copy(self) -> "ChatPrompt":
        return ChatPrompt(
            system=self.system,
            user=self.user,
            messages=copy.deepcopy(self.messages),
            tools=self.tools,
            function_map=self.function_map,
        )

    def set_messages(self, messages: List[Dict[str, Any]]) -> None:
        self.system = None
        self.user = None
        self.messages = copy.deepcopy(messages)

    @classmethod
    def model_validate(
        cls,
        obj: Any,
        *,
        strict: Optional[bool] = None,
        from_attributes: Optional[bool] = None,
        context: Optional[Any] = None,
        by_alias: Optional[bool] = None,
        by_name: Optional[bool] = None,
    ) -> "ChatPrompt":
        """Custom validation method to handle nested objects during deserialization."""
        return ChatPrompt(
            system=obj.get("system", None),
            prompt=obj.get("prompt", None),
            messages=obj.get("messages", None),
        )
