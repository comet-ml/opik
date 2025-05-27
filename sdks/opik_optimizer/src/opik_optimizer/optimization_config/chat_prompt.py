from typing import Any, Dict, List, Literal

from pydantic import BaseModel, Field


class Tool(BaseModel):
    name: str =Field(
        ...,
        description="Name of the tool"
    )
    description: str = Field(
        ...,
        description="Description of the tool"
    )
    parameters: Dict[str, Any] = Field(
        ...,
        description="JSON Schema defining the input parameters for the tool"
    )

class ChatPrompt:
    system: str
    prompt: str
    messages: List[Dict[Literal["role", "content"], str]]

    def __init__(
        self,
        system: str = None,
        prompt: str = None,
        messages: List[Dict[Literal["role", "content"], str]] = None,
        tools: List[Tool] = None
    ):
        self.system = system
        self.prompt = prompt
        self.messages = messages

        self.formatted_messages = self._standardize_prompts()

    def _standardize_prompts(
        self, **kwargs: Any
    ) -> List[Dict[Literal["role", "content"], str]]:
        if (self.system is None and self.prompt is None and self.messages is None):
            raise ValueError(
                "At least one of `system`, `prompt` or `messages` must be provided"
            )

        if (self.prompt is not None and self.messages is not None):
            raise ValueError(
                "`prompt` and `messages` cannot be provided together"
            )
        
        if (self.system is not None and not isinstance(self.system, str)):
            raise ValueError(
                "`system` must be a string"
            )
        
        if (self.prompt is not None and not isinstance(self.prompt, str)):
            raise ValueError(
                "`prompt` must be a string"
            )

        if (self.messages is not None and not isinstance(self.messages, list)):
            raise ValueError(
                "`messages` must be a list"
            )

        standardize_messages = []
            
        if (self.system is not None):
            standardize_messages.append({"role": "system", "content": self.system})
        
        if (self.prompt is not None):
            standardize_messages.append({"role": "user", "content": self.prompt})
        
        if (self.messages is not None):
            for message in self.messages:
                standardize_messages.append(message)
        
        return standardize_messages

    def format(self, **kwargs: Any) -> str:
        return self.prompt.format(**kwargs)
