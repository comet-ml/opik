from typing import Any, TYPE_CHECKING
import json
import os
import logging

from opik.opik_context import get_current_span_data

logger = logging.getLogger(__name__)

import litellm
from litellm.integrations.opik.opik import OpikLogger

from . import _throttle

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

if TYPE_CHECKING:
    from .api_objects import chat_prompt


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


class OptimizableAgent:
    """
    An agent class to subclass to make an Optimizable Agent.

    Attributes:
        model (Optional[str]): The model to use for the agent
        model_kwargs (Dict[str, Any]): Additional keyword arguments for the model
        project_name (Optional[str]): The project name for tracking
    """

    model: str | None = None
    model_kwargs: dict[str, Any] = {}
    input_dataset_field: str | None = None
    prompts: dict[str, "chat_prompt.ChatPrompt"]
    prompt: "chat_prompt.ChatPrompt"
    optimizer: Any | None = None

    def __init__(
        self, prompt: "chat_prompt.ChatPrompt", project_name: str | None = None
    ) -> None:
        """
        Initialize the OptimizableAgent.

        Args:
            prompt: a chat prompt
            project_name: Optional project name for Opik tracking
        """
        self.project_name = project_name or "Default Project"
        self.init_llm()
        self.init_agent(prompt)

    def init_llm(self) -> None:
        """Initialize the LLM with the appropriate callbacks."""
        # Litellm bug requires this (maybe problematic if multi-threaded)
        if "OPIK_PROJECT_NAME" not in os.environ:
            os.environ["OPIK_PROJECT_NAME"] = str(self.project_name)
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]

    def init_agent(self, prompt: "chat_prompt.ChatPrompt") -> None:
        """Bind the runtime prompt and snapshot its model configuration."""
        # Register the tools, if any, for default LiteLLM Agent use:
        self.prompt = prompt
        if getattr(prompt, "model", None) is not None:
            self.model = prompt.model
        if getattr(prompt, "model_kwargs", None) is not None:
            # Use a shallow copy to avoid deepcopying unpicklable objects (e.g., locks
            # added by monitoring/clients inside model kwargs).
            self.model_kwargs = dict(prompt.model_kwargs or {})
        else:
            self.model_kwargs = {}
        self._clamp_model_kwargs_to_limit()

    @_throttle.rate_limited(_limiter)
    def _llm_complete(
        self,
        messages: list[dict[str, str]],
        tools: list[dict[str, str]] | None,
        seed: int | None = None,
    ) -> Any:
        # Merge any caller-provided metadata with Opik tracing keys to avoid duplicates.
        model_kwargs = dict(self.model_kwargs)
        if "metadata" in model_kwargs:
            existing = model_kwargs["metadata"] or {}
            opik_meta = existing.get("opik") or {}
            opik_meta.setdefault("current_span_data", get_current_span_data())
            opik_meta.setdefault("project_name", self.project_name)
            existing["opik"] = opik_meta
            model_kwargs["metadata"] = existing
        else:
            model_kwargs["metadata"] = {
                "opik": {
                    "current_span_data": get_current_span_data(),
                    "project_name": self.project_name,
                },
            }

        response = litellm.completion(
            model=self.model,
            messages=messages,
            seed=seed,
            tools=tools,
            **model_kwargs,
        )
        return response

    def _clamp_model_kwargs_to_limit(self) -> None:
        """Clamp max_tokens fields to the provider context window when known."""
        try:
            token_counter = getattr(litellm, "token_counter", None)
            if not token_counter or not hasattr(
                token_counter, "get_model_context_window"
            ):
                return
            limit = token_counter.get_model_context_window(
                model=self.model, messages=None, tokens=None
            )
            if not limit:
                return
            if "max_tokens" in self.model_kwargs:
                mt = self.model_kwargs.get("max_tokens")
                if isinstance(mt, (int, float)) and mt > limit:
                    logger.warning(
                        "Clamping max_tokens from %s to provider limit %s for model %s",
                        mt,
                        limit,
                        self.model,
                    )
                    self.model_kwargs["max_tokens"] = limit
            if "max_completion_tokens" in self.model_kwargs:
                mct = self.model_kwargs.get("max_completion_tokens")
                if isinstance(mct, (int, float)) and mct > limit:
                    logger.warning(
                        "Clamping max_completion_tokens from %s to provider limit %s for model %s",
                        mct,
                        limit,
                        self.model,
                    )
                    self.model_kwargs["max_completion_tokens"] = limit
        except Exception:
            logger.debug("Unable to clamp model kwargs to token limits", exc_info=True)

    def llm_invoke(
        self,
        query: str | None = None,
        messages: list[dict[str, str]] | None = None,
        seed: int | None = None,
        allow_tool_use: bool | None = False,
    ) -> str:
        """
        NOTE: this is the default LiteLLM API. It is used
        internally for the LiteLLM Agent.

        Invoke the LLM with the provided query or messages.

        Args:
            query (Optional[str]): The query to send to the LLM
            messages (Optional[List[Dict[str, str]]]): Messages to send to the LLM
            seed (Optional[int]): Seed for reproducibility
            allow_tool_use: If True, allow LLM to use tools

        Returns:
            str: The LLM's response
        """
        all_messages = []
        if messages is not None:
            all_messages.extend(messages)

        if query is not None:
            all_messages.append({"role": "user", "content": query})

        if allow_tool_use and self.prompt.tools:
            # Tool-calling loop
            final_response = "I was unable to find the desired information."
            count = 0
            while count < 20:
                count += 1
                response = self._llm_complete(all_messages, self.prompt.tools, seed)
                optimizer_ref = self.optimizer
                if optimizer_ref is not None and hasattr(
                    optimizer_ref, "_increment_llm_counter"
                ):
                    optimizer_ref._increment_llm_counter()
                msg = response.choices[0].message
                all_messages.append(msg.to_dict())
                if msg.tool_calls:
                    for tool_call in msg["tool_calls"]:
                        tool_name = tool_call["function"]["name"]
                        arguments = json.loads(tool_call["function"]["arguments"])

                        tool_func = self.prompt.function_map.get(tool_name)
                        try:
                            tool_result = (
                                tool_func(**arguments)
                                if tool_func is not None
                                else "Unknown tool"
                            )
                        except Exception:
                            tool_result = f"Error in calling tool `{tool_name}`"
                        all_messages.append(
                            {
                                "role": "tool",
                                "tool_call_id": tool_call["id"],
                                "content": str(tool_result),
                            }
                        )
                        # Increment tool call counter if we have access to the optimizer
                        optimizer_ref = self.optimizer
                        if optimizer_ref is not None and hasattr(
                            optimizer_ref, "_increment_tool_counter"
                        ):
                            optimizer_ref._increment_tool_counter()
                else:
                    final_response = msg["content"]
                    break
            result = final_response
        else:
            response = self._llm_complete(all_messages, None, seed)
            optimizer_ref = self.optimizer
            if optimizer_ref is not None and hasattr(
                optimizer_ref, "_increment_llm_counter"
            ):
                optimizer_ref._increment_llm_counter()
            result = response.choices[0].message.content
        return result

    def invoke_dataset_item(self, dataset_item: dict[str, str]) -> str:
        messages = self.prompt.get_messages(dataset_item)
        return self.invoke(messages)

    def invoke(
        self,
        messages: list[dict[str, str]],
        seed: int | None = None,
    ) -> str:
        """
        Invoke the agent with a dataset item.

        Args:
            dataset_item (Dict[str, Any]): The dataset item to process
            seed (Optional[int]): Seed for reproducibility

        Returns:
            Dict[str, Any]: The agent's response
        """
        # Replace with agent invocation:
        result = self.llm_invoke(messages=messages, seed=seed, allow_tool_use=True)
        return result
