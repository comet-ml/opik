from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


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
    prompt: Optional[str]
    messages: Optional[List[Dict[str, str]]]

    def __init__(
        self,
        system: Optional[str] = None,
        prompt: Optional[str] = None,
        messages: Optional[List[Dict[str, str]]] = None,
        tools: Optional[List[Tool]] = None,
    ):
        if system is None and prompt is None and messages is None:
            raise ValueError(
                "At least one of `system`, `prompt`, or `messages` must be provided"
            )

        if prompt is not None and messages is not None:
            raise ValueError("`prompt` and `messages` cannot be provided together")

        if system is not None and messages is not None:
            raise ValueError("`system` and `messages` cannot be provided together")

        if system is not None and not isinstance(system, str):
            raise ValueError("`system` must be a string")

        if prompt is not None and not isinstance(prompt, str):
            raise ValueError("`prompt` must be a string")

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

        self.system = system
        self.prompt = prompt
        self.messages = messages

    def get_system_prompt(self) -> str:
        """
        Get a system prompt from the ChatPrompt
        """
        if self.system is not None:
            return self.system

        elif self.messages is not None:
            return self.messages[0]["content"]

        else:
            raise Exception("Unable to find a system prompt in ChatPrompt")

    def get_messages(
        self, dataset_item: Optional[Dict[str, str]] = None
    ) -> List[Dict[str, str]]:
        # This is a copy, so we can alter the messages:
        messages = self._standardize_prompts()

        if dataset_item:
            for key, value in dataset_item.items():
                for message in messages:
                    # Only replace user message content:
                    if message["role"] == "user":
                        message["content"] = message["content"].replace(
                            "{" + key + "}", str(value)
                        )
        return messages

    def _standardize_prompts(self, **kwargs: Any) -> List[Dict[str, str]]:
        standardize_messages: List[Dict[str, str]] = []

        if self.system is not None:
            standardize_messages.append({"role": "system", "content": self.system})

        if self.prompt is not None:
            standardize_messages.append({"role": "user", "content": self.prompt})

        if self.messages is not None:
            for message in self.messages:
                standardize_messages.append(message)

        return standardize_messages

    def to_dict(self) -> Dict[str, Any]:
        """Convert ChatPrompt to a dictionary for JSON serialization.

        Returns:
            Dict containing the serializable representation of this ChatPrompt
        """
        return {
            "system": self.system,
            "prompt": self.prompt,
            "messages": self.messages,
        }

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
