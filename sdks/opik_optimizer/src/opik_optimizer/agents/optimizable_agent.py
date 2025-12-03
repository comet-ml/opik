from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from ..api_objects import chat_prompt

from abc import ABC, abstractmethod


class OptimizableAgent(ABC):
    """
    An agent class to subclass to make an Optimizable Agent.
    """

    def __init__(self, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def invoke_agent(
        self,
        prompts: dict[str, "chat_prompt.ChatPrompt"],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        """
        Used for multi-prompt optimization
        """
        pass
