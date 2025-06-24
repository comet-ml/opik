from typing import Any, Dict, List, Optional, Union, Type

import copy

from pydantic import BaseModel, Field

from ..optimizable_agent import OptimizableAgent


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

    system: Optional[str]
    user: Optional[str]
    messages: Optional[List[Dict[str, str]]]
    tools: Optional[Dict[str, Any]]
    agent_class: Type[OptimizableAgent]

    def __init__(
        self,
        system: Optional[str] = None,
        user: Optional[str] = None,
        messages: Optional[List[Dict[str, str]]] = None,
        tools: Optional[Dict[str, Any]] = None,
        agent_class: Optional[Type[OptimizableAgent]] = None,
        model: Optional[str] = None,
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
        if agent_class is None:
            self.model = model
            self.model_kwargs = model_kwargs

            class LiteLLMAgent(OptimizableAgent):
                model = self.model
                model_kwargs = self.model_kwargs

            self.agent_class = LiteLLMAgent
        else:
            self.agent_class = agent_class

        self.system = system
        self.user = user
        self.messages = messages
        self.tools = tools
        # Add defaults to tools:
        if self.tools:
            for tool_key in self.tools:
                if "name" not in self.tools[tool_key]:
                    self.tools[tool_key]["name"] = self.tools[tool_key][
                        "function"
                    ].__name__

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
        # Deep copy the messages list and its contents
        messages = copy.deepcopy(self.messages) if self.messages else None
        tools = copy.deepcopy(self.tools)
        return ChatPrompt(
            system=self.system,
            user=self.user,
            messages=messages,
            tools=tools,
            agent_class=self.agent_class,
        )

    def set_messages(self, messages: List[Dict[str, Any]]) -> None:
        self.system = None
        self.user = None
        self.messages = copy.deepcopy(messages)
