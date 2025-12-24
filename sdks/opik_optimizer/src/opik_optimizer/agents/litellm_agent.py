from ..api_objects import chat_prompt
from .. import _llm_calls, _throttle
import os
from typing import Any
import json
from opik import opik_context
import litellm
from opik.integrations.litellm import track_completion
from . import optimizable_agent


_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class LiteLLMAgent(optimizable_agent.OptimizableAgent):
    def __init__(
        self,
        project_name: str,
        trace_metadata: dict[str, Any] | None = None,
    ) -> None:
        self.project_name = project_name

        self.init_llm()
        self.trace_metadata: dict[str, Any] = {}

    def init_llm(self) -> None:
        """Initialize the LLM with the appropriate callbacks."""
        # Litellm bug requires this (maybe problematic if multi-threaded)
        if "OPIK_PROJECT_NAME" not in os.environ:
            os.environ["OPIK_PROJECT_NAME"] = str(self.project_name)

        # Attach default metadata; subclasses may override per-run via start_bundle_trace.
        self.trace_metadata = {"project_name": self.project_name}

    @_throttle.rate_limited(_limiter)
    def _llm_complete(
        self,
        model: str,
        messages: list[dict[str, str]],
        tools: list[dict[str, str]] | None,
        seed: int | None = None,
        model_kwargs: dict[str, Any] | None = None,
    ) -> Any:
        response = track_completion()(litellm.completion)(
            model=model,
            messages=messages,
            seed=seed,
            tools=tools,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "project_name": self.project_name,
                },
            },
            **(model_kwargs or {}),
        )
        return response

    def invoke_agent(
        self,
        prompts: dict[str, "chat_prompt.ChatPrompt"],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        """Invoke multiple prompts"""
        if len(prompts.keys()) > 1:
            raise ValueError(
                "To optimize multiple prompts, you will need to define a specific agent class."
            )
        else:
            prompt = list(prompts.values())[0]

        messages = prompt.get_messages(dataset_item)

        all_messages = []
        if messages is not None:
            all_messages.extend(messages)

        # Push trace metadata for better visibility (tools/LLM logs in Opik)
        try:
            opik_context.update_current_trace(metadata=self.trace_metadata)
        except Exception:
            pass

        if allow_tool_use and prompt.tools:
            # Tool-calling loop
            final_response = "I was unable to find the desired information."
            count = 0
            while count < 20:
                count += 1
                response = self._llm_complete(
                    model=prompt.model,
                    messages=all_messages,
                    tools=prompt.tools,
                    seed=seed,
                    model_kwargs=prompt.model_kwargs,
                )

                _llm_calls._increment_llm_counter_if_in_optimizer()

                msg = response.choices[0].message
                all_messages.append(msg.to_dict())
                if msg.tool_calls:
                    for tool_call in msg["tool_calls"]:
                        tool_name = tool_call["function"]["name"]
                        arguments = json.loads(tool_call["function"]["arguments"])

                        tool_func = prompt.function_map.get(tool_name)
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
                        _llm_calls._increment_tool_counter_if_in_optimizer()
                else:
                    final_response = msg["content"]
                    break
            result = final_response
        else:
            response = self._llm_complete(
                model=prompt.model,
                messages=all_messages,
                tools=None,
                seed=seed,
                model_kwargs=prompt.model_kwargs,
            )
            _llm_calls._increment_llm_counter_if_in_optimizer()
            result = response.choices[0].message.content
        return result
