"""Agent interface used by optimizers to invoke LLM prompts and score outputs."""

from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from ..api_objects import chat_prompt

from abc import ABC, abstractmethod


class OptimizableAgent(ABC):
    """
    Base agent interface for optimizer-driven prompt evaluation.

    Implementations should translate prompt templates + dataset items into model
    calls, and return outputs suitable for scoring. Optimizers may request either
    a single response (invoke_agent) or a list of candidates for pass@k selection
    (invoke_agent_candidates).
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
        Execute the prompt(s) for one dataset item and return a single output string.

        Implementations should honor any model parameters (like temperature/max_tokens)
        embedded in the ChatPrompt, and use the dataset_item to format messages.
        """
        pass

    def invoke_agent_candidates(
        self,
        prompts: dict[str, "chat_prompt.ChatPrompt"],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> list[str]:
        """
        Return candidate outputs for pass@k selection.

        Optimizers use this when a prompt specifies n>1 to evaluate multiple completions
        and choose the best-scoring candidate. By default this wraps invoke_agent and
        returns a single-item list, but agent implementations can override it to surface
        all model choices (one string per candidate).

        Args:
            prompts: Mapping of prompt name to ChatPrompt.
            dataset_item: Dataset row used to render the prompt messages.
            allow_tool_use: Whether tool execution is allowed in this invocation.
            seed: Optional seed for reproducibility.

        Returns:
            List of candidate outputs, ordered as produced by the model.
        """
        return [self.invoke_agent(prompts, dataset_item, allow_tool_use, seed)]
