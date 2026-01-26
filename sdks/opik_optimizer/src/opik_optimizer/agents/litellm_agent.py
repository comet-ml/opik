"""
LiteLLM-backed agent implementation with Opik trace metadata and tool support.

This agent is the default execution layer for optimizers: it renders ChatPrompt
messages, calls LiteLLM for completions, and forwards usage/cost metadata to any
owning optimizer for telemetry and budgeting.
"""

from ..api_objects import chat_prompt
from ..core import llm_calls as _llm_calls
from ..utils import throttle as _throttle
from typing import Any
import json
import logging
import os
from opik import opik_context
import litellm
from opik.integrations.litellm import track_completion
from . import optimizable_agent
from ..constants import resolve_project_name
from ..utils.opik_env import set_project_name_env
from ..utils.logging import debug_tool_call
from ..constants import tool_call_max_iterations
from ..utils.candidate_selection import extract_choice_logprob


logger = logging.getLogger(__name__)
_WARNED_NO_LOGPROBS = False


_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class LiteLLMAgent(optimizable_agent.OptimizableAgent):
    """Concrete OptimizableAgent that delegates execution to LiteLLM."""

    def __init__(
        self,
        project_name: str | None = None,
        trace_metadata: dict[str, Any] | None = None,
    ) -> None:
        self.project_name = resolve_project_name(project_name)
        self.trace_phase = "Prompt Optimization"
        self._warned_no_logprobs = False
        self.init_llm()

    def init_llm(self) -> None:
        """Initialize the LLM with the appropriate callbacks."""
        set_project_name_env(self.project_name)

        # Attach default metadata; subclasses may override per-run via start_bundle_trace.
        self.trace_metadata = {"project_name": self.project_name}

    @_throttle.rate_limited(_limiter)
    def _llm_complete(
        self,
        model: str | None,
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

    def _select_single_prompt(
        self, prompts: dict[str, "chat_prompt.ChatPrompt"]
    ) -> "chat_prompt.ChatPrompt":
        if len(prompts.keys()) > 1:
            raise ValueError(
                "To optimize multiple prompts, you will need to define a specific agent class."
            )
        return list(prompts.values())[0]

    def _build_messages(
        self,
        prompt: "chat_prompt.ChatPrompt",
        dataset_item: dict[str, Any],
    ) -> list[dict[str, Any]]:
        messages = prompt.get_messages(dataset_item)
        all_messages: list[dict[str, Any]] = []
        if messages is not None:
            all_messages.extend(messages)
        return self._prepare_messages(all_messages, dataset_item)

    def _update_trace_metadata(self) -> None:
        try:
            optimizer_ref = getattr(self, "_optimizer_owner", None)
            phase = getattr(self, "trace_phase", None) or "Prompt Optimization"
            if optimizer_ref is not None and hasattr(optimizer_ref, "_tag_trace"):
                optimizer_ref._tag_trace(phase=phase)
            opik_context.update_current_trace(metadata=self.trace_metadata)
        except Exception:
            pass

    def _run_completion(
        self,
        *,
        prompt: "chat_prompt.ChatPrompt",
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        seed: int | None,
    ) -> Any:
        response = self._llm_complete(
            model=prompt.model,
            messages=messages,
            tools=tools,
            seed=seed,
            model_kwargs=self._sanitize_model_kwargs(prompt.model_kwargs),
        )
        _llm_calls._increment_llm_counter_if_in_optimizer()
        self._apply_cost_usage_to_owner(response)
        return response

    def _extract_response_text(self, response: Any) -> str:
        choices = response.choices or []
        if os.getenv("ARC_AGI2_DEBUG", "0") not in {"", "0", "false", "False"}:
            try:
                from opik_optimizer.utils.dataset import resolve_dataset_seed  # noqa: F401
            except Exception:
                pass
        if len(choices) > 1:
            contents = [
                ch.message.content
                for ch in choices
                if hasattr(ch, "message") and getattr(ch, "message").content
            ]
            return "\n\n".join(contents) if contents else ""
        if choices:
            return choices[0].message.content
        return ""

    def _run_tool_call_loop(
        self,
        *,
        prompt: "chat_prompt.ChatPrompt",
        messages: list[dict[str, Any]],
        seed: int | None,
    ) -> str:
        if (prompt.model_kwargs or {}).get("n", 1) != 1:
            # TODO: Support multi-choice tool execution by selecting a single candidate.
            prompt.model_kwargs["n"] = 1
        final_response = "I was unable to find the desired information."
        last_tool_response: str | None = None
        count = 0
        max_iterations = tool_call_max_iterations()
        if max_iterations <= 0:
            return "Tool-calling loop aborted (max_iterations <= 0)."
        while count < max_iterations:
            count += 1
            response = self._run_completion(
                prompt=prompt, messages=messages, tools=prompt.tools, seed=seed
            )

            msg = response.choices[0].message
            messages.append(msg.to_dict())
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
                    last_tool_response = str(tool_result)
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": tool_call["id"],
                            "content": str(tool_result),
                        }
                    )
                    debug_tool_call(
                        tool_name=tool_name,
                        arguments=arguments,
                        result=tool_result,
                        tool_call_id=tool_call["id"],
                    )
                    _llm_calls._increment_llm_call_tools_counter_if_in_optimizer()
            else:
                final_response = msg["content"]
                break
        if count >= max_iterations and msg.tool_calls:
            final_response = (
                last_tool_response
                if last_tool_response is not None
                else (
                    "Tool-calling loop aborted after reaching the maximum of "
                    f"{max_iterations} iterations."
                )
            )
        return final_response

    def invoke_agent(
        self,
        prompts: dict[str, "chat_prompt.ChatPrompt"],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        """Invoke multiple prompts"""
        prompt = self._select_single_prompt(prompts)
        all_messages = self._build_messages(prompt, dataset_item)
        self._update_trace_metadata()
        if allow_tool_use and prompt.tools:
            return self._run_tool_call_loop(
                prompt=prompt, messages=all_messages, seed=seed
            )

        response = self._run_completion(
            prompt=prompt, messages=all_messages, tools=None, seed=seed
        )
        return self._extract_response_text(response)

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

        self._update_trace_metadata()

        response = self._llm_complete(
            model=prompt.model,
            messages=all_messages,
            tools=None,
            seed=seed,
            model_kwargs=self._sanitize_model_kwargs(prompt.model_kwargs),
        )
        _llm_calls._increment_llm_counter_if_in_optimizer()
        self._apply_cost_usage_to_owner(response)

        global _WARNED_NO_LOGPROBS
        choices = response.choices or []
        candidate_logprobs: list[float] = []
        if len(choices) > 1:
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
            elif not self._warned_no_logprobs and not _WARNED_NO_LOGPROBS:
                logger.debug(
                    "LiteLLMAgent: no logprobs available; max_logprob will fall back"
                )
                self._warned_no_logprobs = True
                _WARNED_NO_LOGPROBS = True
        else:
            self._last_candidate_logprobs = None
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
