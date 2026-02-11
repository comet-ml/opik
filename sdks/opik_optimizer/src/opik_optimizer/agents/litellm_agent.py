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
from ..utils import prompt_tracing
from ..utils.toolcalling.normalize.tool_factory import resolve_toolcalling_tools
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from litellm.types.utils import Choices, Usage


logger = logging.getLogger(__name__)
_WARNED_NO_LOGPROBS = False
_SENSITIVE_ARGUMENT_KEYS = (
    "key",
    "token",
    "secret",
    "password",
    "passwd",
    "api_key",
)
_MAX_LOG_VALUE_LENGTH = 48


def _sanitize_tool_arguments_for_logging(arguments: Any) -> Any:
    """Return a redacted copy of tool-call arguments for exception logs."""
    if isinstance(arguments, dict):
        sanitized: dict[str, Any] = {}
        for key, value in arguments.items():
            key_text = str(key)
            key_lower = key_text.lower()
            if any(secret in key_lower for secret in _SENSITIVE_ARGUMENT_KEYS):
                sanitized[key_text] = "***REDACTED***"
            else:
                sanitized[key_text] = _sanitize_tool_arguments_for_logging(value)
        return sanitized
    if isinstance(arguments, list):
        return [_sanitize_tool_arguments_for_logging(item) for item in arguments]
    if isinstance(arguments, tuple):
        return tuple(_sanitize_tool_arguments_for_logging(item) for item in arguments)
    if isinstance(arguments, str):
        lowered = arguments.lower()
        if any(secret in lowered for secret in _SENSITIVE_ARGUMENT_KEYS):
            return "***REDACTED***"
        if len(arguments) > _MAX_LOG_VALUE_LENGTH:
            return f"{arguments[:_MAX_LOG_VALUE_LENGTH]}..."
        return arguments
    return arguments


def _patch_litellm_choices_logprobs() -> None:
    """
    Workaround for LiteLLM bug where Choices.__init__ tries to delete
    a non-existent 'logprobs' attribute, causing AttributeError.

    This patches both __delattr__ and __init__ to safely handle missing logprobs.
    """
    try:
        from litellm.types.utils import Choices

        # Patch __delattr__ to ignore deletion of non-existent logprobs
        if not hasattr(Choices, "_opik_patched_delattr"):
            original_delattr = Choices.__delattr__

            def patched_delattr(self: "Choices", name: str) -> None:
                # If trying to delete logprobs and it doesn't exist, just return
                if name == "logprobs" and not hasattr(self, "logprobs"):
                    return
                # Otherwise, call the original __delattr__
                try:
                    original_delattr(self, name)
                except AttributeError as e:
                    # If the error is specifically about logprobs, ignore it
                    if "'Choices' object has no attribute 'logprobs'" in str(e):
                        return
                    # Otherwise, re-raise
                    raise

            Choices.__delattr__ = patched_delattr  # type: ignore[assignment]
            Choices._opik_patched_delattr = True  # type: ignore[attr-defined]

        # Also patch __init__ to catch any AttributeError during initialization
        if not hasattr(Choices.__init__, "_opik_patched"):
            original_init = Choices.__init__

            def patched_init(self: "Choices", *args: Any, **kwargs: Any) -> None:
                try:
                    original_init(self, *args, **kwargs)
                except AttributeError as e:
                    # If the error is about logprobs, set it to None and continue
                    if "'Choices' object has no attribute 'logprobs'" in str(e):
                        # Ensure logprobs exists before continuing
                        if not hasattr(self, "logprobs"):
                            object.__setattr__(self, "logprobs", None)
                    else:
                        raise

            patched_init._opik_patched = True  # type: ignore[attr-defined]
            Choices.__init__ = patched_init
    except (ImportError, AttributeError):
        # If we can't patch it, that's okay - the error will surface elsewhere
        pass


def _patch_litellm_usage_server_tool_use() -> None:
    """
    Workaround for LiteLLM bug where Usage.__init__ tries to delete
    a non-existent 'server_tool_use' attribute, causing AttributeError.

    This patches both __delattr__ and __init__ to safely handle missing server_tool_use.
    """
    try:
        from litellm.types.utils import Usage

        # Patch __delattr__ to ignore deletion of non-existent server_tool_use
        if not hasattr(Usage, "_opik_patched_delattr"):
            original_delattr = Usage.__delattr__

            def patched_delattr(self: "Usage", name: str) -> None:
                # If trying to delete server_tool_use and it doesn't exist, just return
                if name == "server_tool_use" and not hasattr(self, "server_tool_use"):
                    return
                # Otherwise, call the original __delattr__
                try:
                    original_delattr(self, name)
                except AttributeError as e:
                    # If the error is specifically about server_tool_use, ignore it
                    if "'Usage' object has no attribute 'server_tool_use'" in str(e):
                        return
                    # Otherwise, re-raise
                    raise

            Usage.__delattr__ = patched_delattr  # type: ignore[assignment]
            Usage._opik_patched_delattr = True  # type: ignore[attr-defined]

        # Also patch __init__ to catch any AttributeError during initialization
        if not hasattr(Usage.__init__, "_opik_patched"):
            original_init = Usage.__init__

            def patched_init(self: "Usage", *args: Any, **kwargs: Any) -> None:
                try:
                    original_init(self, *args, **kwargs)
                except AttributeError as e:
                    # If the error is about server_tool_use, set it to None and continue
                    if "'Usage' object has no attribute 'server_tool_use'" in str(e):
                        # Ensure server_tool_use exists before continuing
                        if not hasattr(self, "server_tool_use"):
                            object.__setattr__(self, "server_tool_use", None)
                    else:
                        raise

            patched_init._opik_patched = True  # type: ignore[attr-defined]
            Usage.__init__ = patched_init
    except (ImportError, AttributeError):
        # If we can't patch it, that's okay - the error will surface elsewhere
        pass


# Apply the patches at module import time
_patch_litellm_choices_logprobs()
_patch_litellm_usage_server_tool_use()


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

        # Normalize span data after LiteLLM call to ensure input/output are dicts
        # This prevents issues where the LiteLLM integration might set these to lists
        try:
            from ..utils import prompt_tracing

            prompt_tracing._normalize_current_span_data()
        except Exception:
            # Silently fail - this is a defensive measure
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
        query: str | None = None,
        messages: list[dict[str, str]] | None = None,
        *,
        prompt: "chat_prompt.ChatPrompt | None" = None,
        dataset_item: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        if prompt is not None and dataset_item is not None:
            messages = prompt.get_messages(dataset_item)
            all_messages: list[dict[str, Any]] = []
            if messages is not None:
                all_messages.extend(messages)
            return self._prepare_messages(all_messages, dataset_item)

        all_messages = super()._build_messages(query, messages)
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
        prompt_tracing.attach_span_prompt_payload(prompt)
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
        if prompt.tools is None:
            raise ValueError("prompt.tools must be set before tool-enabled invocation")
        # Normalize MCP tool entries into function-calling tools + callables.
        tools_for_call, function_map = resolve_toolcalling_tools(
            prompt.tools, prompt.function_map
        )
        final_response = "I was unable to find the desired information."
        last_tool_response: str | None = None
        count = 0
        max_iterations = tool_call_max_iterations()
        if max_iterations <= 0:
            return "Tool-calling loop aborted (max_iterations <= 0)."
        while count < max_iterations:
            count += 1
            response = self._run_completion(
                prompt=prompt, messages=messages, tools=tools_for_call, seed=seed
            )

            msg = response.choices[0].message
            # Tool-call turns often arrive with content=None and only tool_calls;
            # normalize to empty string so downstream Pydantic schemas don't warn.
            if getattr(msg, "content", None) is None:
                msg.content = ""
            msg_dict = msg.to_dict()
            if msg_dict.get("content") is None:
                msg_dict["content"] = ""
            messages.append(msg_dict)
            if msg.tool_calls:
                for tool_call in msg["tool_calls"]:
                    tool_name = tool_call["function"]["name"]
                    raw_arguments = tool_call["function"].get("arguments", "{}")
                    arguments: dict[str, Any]
                    tool_result: Any
                    argument_error: str | None = None
                    if isinstance(raw_arguments, dict):
                        parsed_arguments = raw_arguments
                    else:
                        try:
                            parsed_arguments = json.loads(raw_arguments)
                        except (json.JSONDecodeError, TypeError) as exc:
                            parsed_arguments = {}
                            argument_error = (
                                f"Invalid JSON arguments for tool `{tool_name}`: {exc}"
                            )
                    if not isinstance(parsed_arguments, dict):
                        parsed_arguments = {}
                        argument_error = (
                            f"Tool `{tool_name}` arguments must be a JSON object."
                        )
                    arguments = parsed_arguments

                    tool_func = function_map.get(tool_name)
                    if argument_error is not None:
                        safe_raw_arguments = _sanitize_tool_arguments_for_logging(
                            raw_arguments
                        )
                        logger.warning(
                            "Skipping tool call due to invalid arguments name=%s args=%r",
                            tool_name,
                            safe_raw_arguments,
                        )
                        tool_result = argument_error
                    else:
                        try:
                            if tool_func is None:
                                tool_result = f"Unknown tool `{tool_name}`"
                            else:
                                tool_result = tool_func(**arguments)
                        except Exception as exc:  # pragma: no cover - defensive logging
                            safe_arguments = _sanitize_tool_arguments_for_logging(
                                arguments
                            )
                            logger.exception(
                                "Tool call failed name=%s args=%s",
                                tool_name,
                                safe_arguments,
                            )
                            tool_result = f"Error calling tool `{tool_name}`: {exc}"
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
                if msg["content"]:
                    break
                if last_tool_response is not None:
                    follow_up = self._run_completion(
                        prompt=prompt, messages=messages, tools=None, seed=seed
                    )
                    final_response = self._extract_response_text(follow_up)
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
        all_messages = self._build_messages(prompt=prompt, dataset_item=dataset_item)
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
