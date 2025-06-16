from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class Tool(BaseModel):
    name: str = Field(..., description="Name of the tool")
    description: str = Field(..., description="Description of the tool")
    parameters: Dict[str, Any] = Field(
        ..., description="JSON Schema defining the input parameters for the tool"
    )


class ChatPrompt:
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
        self.system = system
        self.prompt = prompt
        self.messages = messages

        self.formatted_messages = self._standardize_prompts()

    def _standardize_prompts(self, **kwargs: Any) -> List[Dict[str, str]]:
        if self.system is None and self.prompt is None and self.messages is None:
            raise ValueError(
                "At least one of `system`, `prompt` or `messages` must be provided"
            )

        if self.prompt is not None and self.messages is not None:
            raise ValueError("`prompt` and `messages` cannot be provided together")

        if self.system is not None and not isinstance(self.system, str):
            raise ValueError("`system` must be a string")

        if self.prompt is not None and not isinstance(self.prompt, str):
            raise ValueError("`prompt` must be a string")

        if self.messages is not None and not isinstance(self.messages, list):
            raise ValueError("`messages` must be a list")

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
            "formatted_messages": self.formatted_messages,
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

    def get_system_prompt(self) -> str:
        if self.system is not None:
            return self.system

        elif self.prompt is not None:
            return self.prompt

        elif self.messages is not None:
            return self.messages[0]["content"]

        else:
            raise Exception("Unable to find a system prompt in ChatPrompt")
