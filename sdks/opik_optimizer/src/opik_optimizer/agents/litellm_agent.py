"""
LiteLLM-backed agent implementation with Opik trace metadata and tool support.

This agent is the default execution layer for optimizers: it renders ChatPrompt
messages, calls LiteLLM for completions, and forwards usage/cost metadata to any
owning optimizer for telemetry and budgeting.
"""

from ..api_objects import chat_prompt
from .. import _llm_calls, _throttle
import os
from typing import Any
import json
import logging
from opik import opik_context
import litellm
from opik.integrations.litellm import track_completion
from . import optimizable_agent
from ..utils.candidate_selection import extract_choice_logprob


logger = logging.getLogger(__name__)


_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class LiteLLMAgent(optimizable_agent.OptimizableAgent):
    """Concrete OptimizableAgent that delegates execution to LiteLLM."""

    def __init__(
        self,
        project_name: str,
        trace_metadata: dict[str, Any] | None = None,
    ) -> None:
        self.project_name = project_name
        self.init_llm()

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
        # Surface token usage/cost if litellm returned it
        try:
            response._opik_cost = getattr(response, "cost", None)
            if hasattr(response, "usage") and response.usage is not None:
                usage_obj = response.usage
                response._opik_usage = {
                    "prompt_tokens": getattr(usage_obj, "prompt_tokens", 0),
                    "completion_tokens": getattr(usage_obj, "completion_tokens", 0),
                    "total_tokens": getattr(usage_obj, "total_tokens", 0),
                }
        except Exception:
            pass
        return response

    def _apply_cost_usage_to_owner(self, response: Any) -> None:
        """Propagate cost/usage to the owning optimizer if available."""
        try:
            optimizer_candidate = getattr(self, "_optimizer_owner", None)
            if optimizer_candidate is not None:
                optimizer_candidate._add_llm_cost(getattr(response, "_opik_cost", None))
                optimizer_candidate._add_llm_usage(
                    getattr(response, "_opik_usage", None)
                )
        except Exception:
            pass

    def _sanitize_model_kwargs(
        self, model_kwargs: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """Strip optimizer-only keys before sending kwargs to LiteLLM."""
        if not model_kwargs:
            return model_kwargs
        sanitized = dict(model_kwargs)
        sanitized.pop("selection_policy", None)
        sanitized.pop("candidate_selection_policy", None)
        return sanitized

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

        all_messages = self._prepare_messages(all_messages, dataset_item)

        # Push trace metadata for better visibility (tools/LLM logs in Opik)
        try:
            opik_context.update_current_trace(metadata=self.trace_metadata)
        except Exception:
            pass

        if allow_tool_use and prompt.tools:
            if (prompt.model_kwargs or {}).get("n", 1) != 1:
                # TODO: Support multi-choice tool execution by selecting a single candidate.
                prompt.model_kwargs["n"] = 1
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
                    model_kwargs=self._sanitize_model_kwargs(prompt.model_kwargs),
                )

                _llm_calls._increment_llm_counter_if_in_optimizer()
                self._apply_cost_usage_to_owner(response)

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
                model_kwargs=self._sanitize_model_kwargs(prompt.model_kwargs),
            )
            _llm_calls._increment_llm_counter_if_in_optimizer()
            self._apply_cost_usage_to_owner(response)
            choices = response.choices or []
            if os.getenv("ARC_AGI2_DEBUG", "0") not in {"", "0", "false", "False"}:
                try:
                    # Lightweight debug to confirm number of completions returned
                    from opik_optimizer.utils.dataset_utils import resolve_dataset_seed  # noqa: F401
                except Exception:
                    pass
            if len(choices) > 1:
                contents = [
                    ch.message.content
                    for ch in choices
                    if hasattr(ch, "message") and getattr(ch, "message").content
                ]
                result = "\n\n".join(contents) if contents else ""
            elif choices:
                result = choices[0].message.content
            else:
                result = ""
        return result

    def invoke_agent_candidates(
        self,
        prompts: dict[str, "chat_prompt.ChatPrompt"],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> list[str]:
        """
        Return one output per LiteLLM choice for pass@k evaluation.

        When the prompt requests n>1 completions and tool calls are disabled, this
        surfaces each choice as its own candidate so the optimizer can score and
        select the best output. If tools are enabled, the method falls back to a
        single candidate because tool execution currently assumes n=1.
        """
        if len(prompts.keys()) > 1:
            raise ValueError(
                "To optimize multiple prompts, you will need to define a specific agent class."
            )
        prompt = list(prompts.values())[0]

        if allow_tool_use and prompt.tools:
            if (prompt.model_kwargs or {}).get("n", 1) != 1:
                # TODO: Support multi-choice tool execution by selecting a single candidate.
                prompt.model_kwargs["n"] = 1
            return [self.invoke_agent(prompts, dataset_item, allow_tool_use, seed)]

        messages = prompt.get_messages(dataset_item)
        all_messages: list[dict[str, Any]] = []
        if messages is not None:
            all_messages.extend(messages)

        all_messages = self._prepare_messages(all_messages, dataset_item)

        try:
            opik_context.update_current_trace(metadata=self.trace_metadata)
        except Exception:
            pass

        response = self._llm_complete(
            model=prompt.model,
            messages=all_messages,
            tools=None,
            seed=seed,
            model_kwargs=self._sanitize_model_kwargs(prompt.model_kwargs),
        )
        _llm_calls._increment_llm_counter_if_in_optimizer()
        self._apply_cost_usage_to_owner(response)

        choices = response.choices or []
        candidate_logprobs: list[float] = []
        for choice in choices:
            score = extract_choice_logprob(
                choice,
                aggregation="mean",
                min_tokens=5,
            )
            if score is None:
                candidate_logprobs = []
                break
            candidate_logprobs.append(score)
        self._last_candidate_logprobs = (
            candidate_logprobs if candidate_logprobs else None
        )
        if candidate_logprobs:
            logger.debug(
                "LiteLLMAgent: extracted logprobs for %d choices",
                len(candidate_logprobs),
            )
        else:
            logger.debug(
                "LiteLLMAgent: no logprobs available; max_logprob will fall back"
            )
        outputs = [
            ch.message.content
            for ch in choices
            if hasattr(ch, "message") and getattr(ch, "message").content
        ]
        return outputs if outputs else [""]

    def _prepare_messages(
        self, messages: list[dict[str, Any]], dataset_item: dict[str, Any] | None
    ) -> list[dict[str, Any]]:
        """Hook for subclasses to adjust messages before the LLM call."""
        return messages
