from typing import Any
from pydantic import BaseModel
import sys
from types import FrameType

import litellm
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

from . import _throttle

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


def _increment_llm_counter_if_needed() -> None:
    """
    Walk up the call stack and increment the first optimizer's counter if found.
    """
    try:
        from .base_optimizer import BaseOptimizer
    except Exception:
        return

    try:
        frame: FrameType | None = sys._getframe()
    except ValueError:
        return

    while frame is not None:
        optimizer_candidate = frame.f_locals.get("self")
        if isinstance(optimizer_candidate, BaseOptimizer):
            optimizer_candidate._increment_llm_counter()
            break
        frame = frame.f_back


def _build_call_time_params(
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Build dictionary of call-time LiteLLM parameter overrides.

    Args:
        temperature: Sampling temperature (0-2)
        max_tokens: Maximum tokens to generate
        max_completion_tokens: Upper bound for generated tokens
        top_p: Nucleus sampling probability mass
        presence_penalty: Penalty for new tokens based on presence
        frequency_penalty: Penalty for new tokens based on frequency
        metadata: Optional metadata dict for monitoring

    Returns:
        Dictionary of non-None parameters for LiteLLM
    """
    call_time_params: dict[str, Any] = {}
    if temperature is not None:
        call_time_params["temperature"] = temperature
    if max_tokens is not None:
        call_time_params["max_tokens"] = max_tokens
    if max_completion_tokens is not None:
        call_time_params["max_completion_tokens"] = max_completion_tokens
    if top_p is not None:
        call_time_params["top_p"] = top_p
    if presence_penalty is not None:
        call_time_params["presence_penalty"] = presence_penalty
    if frequency_penalty is not None:
        call_time_params["frequency_penalty"] = frequency_penalty
    if metadata is not None:
        call_time_params["metadata"] = metadata
    return call_time_params


def _prepare_model_params(
    model_parameters: dict[str, Any],
    call_time_params: dict[str, Any],
    response_model: type[BaseModel] | None = None,
    is_reasoning: bool = False,
    optimization_id: str | None = None,
    project_name: str | None = None,
) -> dict[str, Any]:
    """
    Prepare parameters for LiteLLM call by merging and adding monitoring.

    Args:
        call_time_params: Dict of LiteLLM params from call-time overrides
        response_model: Optional Pydantic model for structured output
        is_reasoning: Flag for metadata tagging

    Returns:
        Dictionary ready for litellm.completion/acompletion
    """

    # Merge optimizer's model_parameters with call-time overrides
    merged_params = {**model_parameters, **call_time_params}

    # Add Opik monitoring wrapper
    final_params = opik_litellm_monitor.try_add_opik_monitoring_to_params(merged_params)

    # Add reasoning metadata if applicable
    if is_reasoning and "metadata" in final_params:
        if "opik_call_type" not in final_params["metadata"]:
            final_params["metadata"]["opik_call_type"] = "reasoning"

    # Configure project_name and tags for Opik tracing
    metadata = final_params.setdefault("metadata", {})
    opik_metadata = metadata.setdefault("opik", {})

    # Only set project name when provided so caller overrides survive
    if project_name is not None:
        opik_metadata["project_name"] = project_name

    # Add tags if optimization_id is available
    if optimization_id:
        opik_metadata["tags"] = [
            optimization_id,
            "Prompt Optimization",
        ]

    # Add structured output support
    if response_model is not None:
        final_params["response_format"] = response_model

    return final_params


def _parse_response(
    response: Any,
    response_model: type[BaseModel] | None = None,
) -> BaseModel | str:
    """
    Parse LiteLLM response, with optional structured output parsing.

    Args:
        response: The response from litellm.completion/acompletion
        response_model: Optional Pydantic model for structured output

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    content = response.choices[0].message.content

    # When using structured outputs with Pydantic models, LiteLLM automatically
    # parses the response. Parse the JSON string into the Pydantic model
    if response_model is not None:
        return response_model.model_validate_json(content)

    return content


@_throttle.rate_limited(_limiter)
def call_model(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    response_model: type[BaseModel] | None = None,
    is_reasoning: bool = False,
    # Explicit call-time overrides for LiteLLM params
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    # Optimizer-specific metadata (not passed to LiteLLM)
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
) -> BaseModel | str:
    """
    Call the LLM model with optional structured output.

    Args:
        messages: List of message dictionaries with 'role' and 'content' keys
        model: The model to use (defaults to self.model)
        seed: Random seed for reproducibility (defaults to self.seed)
        response_model: Optional Pydantic model for structured output
        is_reasoning: Flag for metadata tagging (not passed to LiteLLM)
        temperature: Sampling temperature (0-2)
        max_tokens: Maximum tokens to generate
        max_completion_tokens: Upper bound for generated tokens
        top_p: Nucleus sampling probability mass
        presence_penalty: Penalty for new tokens based on presence
        frequency_penalty: Penalty for new tokens based on frequency
        optimization_id: Optional ID for optimization tracking (metadata only)
        metadata: Optional metadata dict for monitoring

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    _increment_llm_counter_if_needed()

    # Build dict of call-time LiteLLM parameter overrides (non-None only)
    call_time_params = _build_call_time_params(
        temperature=temperature,
        max_tokens=max_tokens,
        max_completion_tokens=max_completion_tokens,
        top_p=top_p,
        presence_penalty=presence_penalty,
        frequency_penalty=frequency_penalty,
        metadata=metadata,
    )

    if model_parameters is None:
        model_parameters = {}

    final_params_for_litellm = _prepare_model_params(
        model_parameters,
        call_time_params,
        response_model,
        is_reasoning,
        optimization_id,
    )

    response = litellm.completion(
        model=model,
        messages=messages,
        seed=seed,
        num_retries=6,
        **final_params_for_litellm,
    )

    return _parse_response(response, response_model)


@_throttle.rate_limited_async(_limiter)
async def call_model_async(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    project_name: str | None = None,
    response_model: type[BaseModel] | None = None,
    is_reasoning: bool = False,
    # Explicit call-time overrides for LiteLLM params
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    # Optimizer-specific metadata (not passed to LiteLLM)
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
) -> BaseModel | str:
    """
    Async version of _call_model using litellm.acompletion.

    Args:
        messages: List of message dictionaries with 'role' and 'content' keys
        model: The model to use
        seed: Random seed for reproducibility
        response_model: Optional Pydantic model for structured output
        is_reasoning: Flag for metadata tagging (not passed to LiteLLM)
        temperature: Sampling temperature (0-2)
        max_tokens: Maximum tokens to generate
        max_completion_tokens: Upper bound for generated tokens
        top_p: Nucleus sampling probability mass
        presence_penalty: Penalty for new tokens based on presence
        frequency_penalty: Penalty for new tokens based on frequency
        optimization_id: Optional ID for optimization tracking (metadata only)
        metadata: Optional metadata dict for monitoring

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    _increment_llm_counter_if_needed()

    # Build dict of call-time LiteLLM parameter overrides (non-None only)
    call_time_params = _build_call_time_params(
        temperature=temperature,
        max_tokens=max_tokens,
        max_completion_tokens=max_completion_tokens,
        top_p=top_p,
        presence_penalty=presence_penalty,
        frequency_penalty=frequency_penalty,
        metadata=metadata,
    )

    if model_parameters is None:
        model_parameters = {}

    final_params_for_litellm = _prepare_model_params(
        model_parameters=model_parameters,
        call_time_params=call_time_params,
        response_model=response_model,
        is_reasoning=is_reasoning,
        optimization_id=optimization_id,
        project_name=project_name,
    )

    response = await litellm.acompletion(
        model=model,
        messages=messages,
        seed=seed,
        num_retries=6,
        **final_params_for_litellm,
    )

    return _parse_response(response, response_model)
