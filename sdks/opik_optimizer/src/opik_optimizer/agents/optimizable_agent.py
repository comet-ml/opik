from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from ..api_objects import chat_prompt

from abc import ABC, abstractmethod

def tools_to_dict(tools: dict[str, dict[str, Any]]) -> dict[str, Any]:
    retval = {}
    for name in tools:
        parts = {}
        for part in tools[name]:
            if isinstance(tools[name][part], (int, float, str)):
                parts[part] = tools[name][part]
        if parts:
            retval[name] = parts
    return retval

class OptimizableAgent(ABC):
    """
    An agent class to subclass to make an Optimizable Agent.
    """

    def __init__(self, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def invoke(
        self,
        messages: list[dict[str, str]] | None = None,
        seed: int | None = None,
        allow_tool_use: bool = True,
    ) -> str:
        """
        Invoke the LLM with the provided query or messages.

        Args:
            messages (Optional[List[Dict[str, str]]]): Messages to send to the LLM
            seed (Optional[int]): Seed for reproducibility
            allow_tool_use: If True, allow LLM to use tools

        Returns:
            str: The LLM's response
        """
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
