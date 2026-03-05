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


MESSAGE_KEYS: list[tuple[str, str]] = [
    ("system_prompt", "system"),
    ("user_message", "user"),
]


class LLMChatTask:
    """Task that builds LLM messages from flat config keys.

    Reads named prompt parameters (system_prompt, user_message) from the config,
    constructs the messages list, substitutes dataset item values into
    template placeholders, then calls litellm.completion().
    """

    def __init__(self, config: CandidateConfig) -> None:
        self.messages: list[dict[str, str]] = []
        for key, role in MESSAGE_KEYS:
            content = config.get(key)
            if content is not None:
                self.messages.append({"role": role, "content": str(content)})
        self.model: str = config["model"]
        self.model_parameters: dict[str, Any] = config.get("model_parameters", {})

    def __call__(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        formatted_messages = []
        for msg in self.messages:
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
    LLMChatTask when config has flat prompt keys (system_prompt, user_message),
    otherwise falls back to LLMTask with prompt_messages list.
    """
    if config.get("mask_id") is not None:
        return RemoteExecutionTask(mask_id=config["mask_id"])

    if any(config.get(key) is not None for key, _ in MESSAGE_KEYS):
        return LLMChatTask(config)

    return LLMTask(
        prompt_messages=config["prompt_messages"],
        model=config["model"],
        model_parameters=config.get("model_parameters", {}),
    )
