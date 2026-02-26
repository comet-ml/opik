from __future__ import annotations

from typing import Any, Protocol

import litellm

from opik_optimizer_framework.types import CandidateConfig


class Task(Protocol):
    """Protocol for optimization tasks.

    A task takes a dataset item and returns a result dict with at least
    "input" and "output" keys so that suite evaluators can score the output.
    """

    def __call__(self, dataset_item: dict[str, Any]) -> dict[str, Any]: ...


class LLMTask:
    """Task that runs an LLM completion via litellm.

    Formats prompt_messages by substituting dataset item values into
    template placeholders, then calls litellm.completion().
    """

    def __init__(
        self,
        prompt_messages: list[dict[str, str]],
        model: str,
        model_parameters: dict[str, Any],
    ) -> None:
        self.prompt_messages = prompt_messages
        self.model = model
        self.model_parameters = model_parameters

    def __call__(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        formatted_messages = []
        for msg in self.prompt_messages:
            content = msg["content"]
            for key, value in dataset_item.items():
                content = content.replace(f"{{{key}}}", str(value))
            formatted_messages.append({"role": msg["role"], "content": content})

        response = litellm.completion(
            model=self.model,
            messages=formatted_messages,
            **self.model_parameters,
        )
        return {
            "input": dataset_item,
            "output": response.choices[0].message.content,
        }


class RemoteExecutionTask:
    """Task that triggers a remote agent run via mask_id.

    The remote agent picks up the candidate configuration and executes
    the task externally. Results are retrieved after execution completes.
    """

    def __init__(self, mask_id: str) -> None:
        self.mask_id = mask_id

    def __call__(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        raise NotImplementedError(
            "RemoteExecutionTask is not yet implemented. "
            "This will trigger a remote agent run using the mask_id."
        )


def create_task(config: CandidateConfig) -> Task:
    """Factory that selects the appropriate Task implementation.

    Routes to RemoteExecutionTask when config has a mask_id,
    otherwise falls back to LLMTask.
    """
    if config.mask_id is not None:
        return RemoteExecutionTask(mask_id=config.mask_id)

    return LLMTask(
        prompt_messages=config.prompt_messages,
        model=config.model,
        model_parameters=config.model_parameters,
    )
