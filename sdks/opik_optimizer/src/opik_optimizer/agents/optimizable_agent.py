"""Agent interface used by optimizers to invoke LLM prompts and score outputs."""

from abc import ABC
from typing import Any, TYPE_CHECKING
import json
import copy

import litellm
from litellm.integrations.opik.opik import OpikLogger
from opik import opik_context
from opik.integrations.litellm import track_completion
from ..constants import resolve_project_name, tool_call_max_iterations
from ..utils.opik_env import set_project_name_env
from ..utils import throttle as _throttle
from ..utils.logging import debug_tool_call
from ..utils.toolcalling.normalize.tool_factory import resolve_toolcalling_tools
from ..utils import prompt_tracing

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

if TYPE_CHECKING:
    from ..api_objects import chat_prompt

# FIXME: This class inherits from ABC but provides concrete implementations.
# Consider splitting into OptimizableAgentInterface (ABC) and DefaultOptimizableAgent (concrete)
# or removing ABC inheritance if we want to keep it as a concrete base class.


# TODO: Verify if this function is still used anywhere, remove if unused
def tools_to_dict(tools: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """Convert tools dictionary to a simplified format."""
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
    Base agent interface for optimizer-driven prompt evaluation.

    Implementations should translate prompt templates + dataset items into model
    calls, and return outputs suitable for scoring. Optimizers may request either
    a single response (invoke_agent) or a list of candidates for pass@k selection
    (invoke_agent_candidates).

    This class can be used as an abstract interface (for type checking and subclassing)
    or as a concrete implementation with legacy API support.

    TODO: Consider deprecating legacy API methods (invoke, invoke_prompt, invoke_dataset_item, llm_invoke)
    in favor of the standard invoke_agent/invoke_agent_candidates interface.
    """

    # FIXME: These attributes mix concerns - some are for legacy API, some for new API.
    # Consider separating into distinct attribute groups or using a more structured approach.
    # Attributes for concrete implementation
    model: str | None = None
    model_kwargs: dict[str, Any] = {}
    input_dataset_field: str | None = None
    prompts: dict[str, "chat_prompt.ChatPrompt"] | None = None
    prompt: "chat_prompt.ChatPrompt | None" = None
    optimizer: Any | None = None
    project_name: str | None = None
    trace_metadata: dict[str, Any] | None = None
    trace_phase: str = "Prompt Optimization"

    def __init__(
        self,
        prompt: "chat_prompt.ChatPrompt | None" = None,
        project_name: str | None = None,
        **kwargs: Any,
    ) -> None:
        """
        Initialize the OptimizableAgent.

        Args:
            prompt: Optional chat prompt (for legacy API compatibility).
            project_name: Optional project name for Opik tracking.
            **kwargs: Additional keyword arguments (for abstract interface compatibility).
        """
        self.project_name = resolve_project_name(project_name)
        self.trace_phase = "Prompt Optimization"
        if prompt is not None:
            # TODO: Deprecate legacy API initialization pattern. Prefer using invoke_agent() directly.
            # Legacy API: initialize with prompt
            self.init_llm()
            self.init_agent(prompt)
        else:
            # Abstract interface: minimal initialization
            self.trace_metadata = {"project_name": self.project_name}

    def init_llm(self) -> None:
        """Initialize the LLM with the appropriate callbacks."""
        set_project_name_env(self.project_name)
        # FIXME: Setting global litellm.callbacks can cause issues with multiple agents.
        # Consider using per-instance callbacks or a callback registry.
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]
        # Attach default metadata; subclasses may override per-run via start_bundle_trace.
        self.trace_metadata = {"project_name": self.project_name}

    def init_agent(self, prompt: "chat_prompt.ChatPrompt") -> None:
        """Bind the runtime prompt and snapshot its model configuration."""
        # Register the tools, if any, for default LiteLLM Agent use:
        self.prompt = prompt
        if getattr(prompt, "model", None) is not None:
            self.model = prompt.model
        if getattr(prompt, "model_kwargs", None) is not None:
            self.model_kwargs = copy.deepcopy(prompt.model_kwargs or {})
        else:
            self.model_kwargs = {}

    @_throttle.rate_limited(_limiter)
    def _llm_complete(
        self,
        model: str | None,
        messages: list[dict[str, str]],
        tools: list[dict[str, str]] | None,
        seed: int | None = None,
        model_kwargs: dict[str, Any] | None = None,
    ) -> Any:
        """Make an LLM completion call with rate limiting and Opik tracing."""
        # Use provided model/kwargs or fall back to instance attributes
        effective_model = model if model is not None else self.model
        effective_kwargs = (
            model_kwargs if model_kwargs is not None else self.model_kwargs
        )
        opik_metadata: dict[str, Any] = {
            "current_span_data": opik_context.get_current_span_data()
        }
        if self.project_name:
            opik_metadata["project_name"] = self.project_name
        response = track_completion()(litellm.completion)(
            model=effective_model,
            messages=messages,
            seed=seed,
            tools=tools,
            metadata={
                "opik": opik_metadata,
            },
            **effective_kwargs,
        )

        # Normalize span data after LiteLLM call to ensure input/output are dicts
        # This prevents issues where the LiteLLM integration might set these to lists
        try:
            from ..utils import prompt_tracing

            prompt_tracing._normalize_current_span_data()
        except Exception:
            # Silently fail - this is a defensive measure
            pass

        return response

    # TODO: Deprecate this legacy method. Use invoke_agent() instead for consistency.
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

        DEPRECATED: This is a legacy API method. Prefer using invoke_agent() instead.

        Invoke the LLM with the provided query or messages.

        Args:
            query (Optional[str]): The query to send to the LLM
            messages (Optional[List[Dict[str, str]]]): Messages to send to the LLM
            seed (Optional[int]): Seed for reproducibility
            allow_tool_use: If True, allow LLM to use tools

        Returns:
            str: The LLM's response
        """
        all_messages = self._build_messages(query, messages)
        self._attach_prompt_span(all_messages)
        self._tag_optimizer_trace()
        self._push_trace_metadata()

        if allow_tool_use and self.prompt and self.prompt.tools:
            return self._invoke_with_tools(all_messages, seed)
        return self._invoke_without_tools(all_messages, seed)

    def _build_messages(
        self,
        query: str | None,
        messages: list[dict[str, str]] | None,
        *,
        prompt: "chat_prompt.ChatPrompt | None" = None,
        dataset_item: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        all_messages: list[dict[str, str]] = []
        if messages is not None:
            all_messages.extend(messages)
        if query is not None:
            all_messages.append({"role": "user", "content": query})
        return all_messages

    def _attach_prompt_span(self, messages: list[dict[str, str]]) -> None:
        if self.prompt is not None:
            prompt_tracing.attach_span_prompt_payload(self.prompt)

    def _tag_optimizer_trace(self) -> None:
        optimizer_ref = self.optimizer
        phase = self.trace_phase or "Prompt Optimization"
        if optimizer_ref is not None and hasattr(optimizer_ref, "_tag_trace"):
            try:
                optimizer_ref._tag_trace(phase=phase)
            except Exception:
                pass

    def _push_trace_metadata(self) -> None:
        """Push trace_metadata to opik_context for tool/LLM observability.

        Expects a mapping of keys to optional values; only non-None entries are sent.
        No-op when metadata is empty; runs during tracing after tagging.
        """
        # Push trace metadata for better visibility (tools/LLM logs in Opik)
        if self.trace_metadata:
            filtered_metadata = {
                key: value
                for key, value in self.trace_metadata.items()
                if value is not None
            }
            if filtered_metadata:
                opik_context.update_current_trace(metadata=filtered_metadata)

    def _invoke_with_tools(
        self,
        messages: list[dict[str, str]],
        seed: int | None,
    ) -> str:
        prompt = self.prompt
        if prompt is None:
            raise ValueError("prompt must be set before tool-enabled invocation")
        if prompt.tools is None:
            raise ValueError("prompt.tools must be set before tool-enabled invocation")
        # Normalize MCP tool entries into function-calling tools + callables.
        tools_for_call, function_map = resolve_toolcalling_tools(
            prompt.tools, prompt.function_map
        )
        final_response = "I was unable to find the desired information."
        count = 0
        max_iterations = tool_call_max_iterations()
        while count < max_iterations:
            count += 1
            response = self._llm_complete(self.model, messages, tools_for_call, seed)
            self._increment_llm_counter()
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
                self._handle_tool_calls(msg["tool_calls"], messages, function_map)
            else:
                final_response = msg["content"]
                break
        return final_response

    def _invoke_without_tools(
        self,
        messages: list[dict[str, str]],
        seed: int | None,
    ) -> str:
        response = self._llm_complete(self.model, messages, None, seed)
        self._increment_llm_counter()
        return response.choices[0].message.content

    def _handle_tool_calls(
        self,
        tool_calls: list[dict[str, Any]],
        messages: list[dict[str, str]],
        function_map: dict[str, Any] | None = None,
    ) -> None:
        prompt = self.prompt
        if prompt is None:
            raise ValueError("prompt must be set before handling tool calls")
        if prompt.tools is None:
            raise ValueError("prompt.tools must be set before handling tool calls")
        if function_map is None:
            _, function_map = resolve_toolcalling_tools(
                prompt.tools, prompt.function_map
            )
        for tool_call in tool_calls:
            tool_name = tool_call["function"]["name"]
            arguments = json.loads(tool_call["function"]["arguments"])
            tool_func = function_map.get(tool_name)
            tool_result = (
                tool_func(**arguments) if tool_func is not None else "Unknown tool"
            )
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
            self._increment_llm_call_tools_counter()

    def _increment_llm_counter(self) -> None:
        optimizer_ref = self.optimizer
        if optimizer_ref is not None and hasattr(
            optimizer_ref, "_increment_llm_counter"
        ):
            optimizer_ref._increment_llm_counter()

    def _increment_llm_call_tools_counter(self) -> None:
        optimizer_ref = self.optimizer
        if optimizer_ref is not None and hasattr(
            optimizer_ref, "_increment_llm_call_tools_counter"
        ):
            optimizer_ref._increment_llm_call_tools_counter()

    # TODO: Deprecate this legacy method. Use invoke_agent() instead.
    def invoke_dataset_item(self, dataset_item: dict[str, str]) -> str:
        """Invoke the agent with a dataset item (legacy API)."""
        if self.prompt is None:
            raise ValueError("prompt must be set before calling invoke_dataset_item")
        messages = self.prompt.get_messages(dataset_item)
        return self.invoke(messages)

    # TODO: Deprecate this legacy method. Use invoke_agent() instead.
    # FIXME: The temporary binding pattern (try/finally) is fragile and error-prone.
    # Consider refactoring to avoid mutating instance state.
    def invoke_prompt(
        self,
        prompt: "chat_prompt.ChatPrompt",
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        """
        Invoke a specific ChatPrompt while temporarily binding model/tool context (legacy API).

        DEPRECATED: This is a legacy API method. Prefer using invoke_agent() instead.
        """
        original_prompt = self.prompt
        original_model = self.model
        original_kwargs = self.model_kwargs
        try:
            self.prompt = prompt
            if getattr(prompt, "model", None) is not None:
                self.model = prompt.model
            self.model_kwargs = copy.deepcopy(getattr(prompt, "model_kwargs", {}) or {})
            messages = prompt.get_messages(dataset_item)
            prompt_invoker = getattr(prompt, "invoke", None)
            if callable(prompt_invoker):
                return prompt_invoker(messages)
            return self.invoke(messages=messages, seed=seed)
        finally:
            self.prompt = original_prompt
            self.model = original_model
            self.model_kwargs = original_kwargs

    # TODO: Deprecate this legacy method. Use invoke_agent() instead.
    def invoke(
        self,
        messages: list[dict[str, str]],
        seed: int | None = None,
    ) -> str:
        """
        Invoke the agent with messages (legacy API).

        DEPRECATED: This is a legacy API method. Prefer using invoke_agent() instead.

        Args:
            messages: List of message dictionaries
            seed: Optional seed for reproducibility

        Returns:
            str: The agent's response
        """
        result = self.llm_invoke(messages=messages, seed=seed, allow_tool_use=True)
        return result

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

        This is the primary method used by optimizers. Default implementation bridges
        to the legacy API if a single prompt is provided.

        Subclasses should override this method to provide custom behavior.
        """
        # FIXME: This default implementation bridges to legacy API methods.
        # Once legacy methods are deprecated, this should be made abstract again or
        # provide a proper implementation that doesn't rely on legacy code.
        # Default implementation for backward compatibility
        if len(prompts) > 1:
            raise NotImplementedError(
                "Multiple prompts not supported by default implementation. "
                "Subclass and override invoke_agent to support multiple prompts."
            )
        prompt = list(prompts.values())[0]
        # TODO: Remove this call to legacy invoke_prompt() once legacy API is deprecated
        return self.invoke_prompt(prompt, dataset_item, allow_tool_use, seed)

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
        # TODO: Improve default implementation to actually support pass@k by checking
        # if the model returns multiple choices (n>1) and extracting them properly.
        # Currently just wraps invoke_agent which may not surface all candidates.
        return [self.invoke_agent(prompts, dataset_item, allow_tool_use, seed)]
