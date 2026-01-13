from typing import Any, TypeVar, overload
from pydantic import BaseModel, ValidationError as PydanticValidationError

import json
import logging
import sys
from types import FrameType

import litellm
from litellm.exceptions import BadRequestError
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor
from opik.integrations.litellm import track_completion

from . import _throttle
from . import utils as _utils


def _strip_project_name(params: dict[str, Any]) -> dict[str, Any]:
    """
    Remove project_name from metadata["opik"] before passing to litellm.

    This prevents double-passing of project_name to Opik tracing:
    - track_completion(project_name=X) already sets the project for Opik
    - If metadata["opik"]["project_name"] is also passed, it may conflict

    The flow is:
    1. Caller passes project_name= parameter to call_model/call_model_async
    2. track_completion(project_name=X) wraps the litellm call for Opik tracing
    3. _prepare_model_params may add project_name to metadata for consistency
    4. This function strips it before the actual litellm call to avoid conflicts

    Args:
        params: Dict of parameters prepared for litellm.completion/acompletion

    Returns:
        New dict with metadata["opik"]["project_name"] removed (if present).
        Original dict is not modified.
    """
    metadata = params.get("metadata")
    if not isinstance(metadata, dict):
        return params
    opik_metadata = metadata.get("opik")
    if not isinstance(opik_metadata, dict):
        return params
    if "project_name" not in opik_metadata:
        return params
    updated_params = {**params}
    updated_metadata = {**metadata}
    updated_opik = {**opik_metadata}
    updated_opik.pop("project_name", None)
    if updated_opik:
        updated_metadata["opik"] = updated_opik
    else:
        updated_metadata.pop("opik", None)
    updated_params["metadata"] = updated_metadata
    return updated_params


logger = logging.getLogger(__name__)


def requested_multiple_candidates(model_parameters: dict[str, Any] | None) -> bool:
    """Return True when model parameters request multiple completions (n > 1)."""
    n_value = (model_parameters or {}).get("n", 1) or 1
    try:
        return int(n_value) > 1
    except (TypeError, ValueError):
        return False


_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

# TypeVar for generic response model typing
_T = TypeVar("_T", bound=BaseModel)


def _increment_llm_counter_if_in_optimizer() -> None:
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


def _increment_tool_counter_if_in_optimizer() -> None:
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
            optimizer_candidate._increment_tool_counter()
            break
        frame = frame.f_back


def _get_project_name_from_optimizer() -> str | None:
    """Return project_name from the nearest optimizer on the call stack."""
    try:
        from .base_optimizer import BaseOptimizer
    except Exception:
        return None

    try:
        frame: FrameType | None = sys._getframe()
    except ValueError:
        return None

    while frame is not None:
        optimizer_candidate = frame.f_locals.get("self")
        if isinstance(optimizer_candidate, BaseOptimizer):
            return getattr(optimizer_candidate, "project_name", None)
        frame = frame.f_back
    return None


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


class StructuredOutputParsingError(Exception):
    """Raised when a structured output Pydantic model cannot be parsed."""

    def __init__(self, content: str, error: Exception) -> None:
        super().__init__(f"{error} (content={content!r})")
        self.content = content
        self.error = error


def _parse_response(
    response: Any,
    response_model: type[BaseModel] | None = None,
    return_all: bool = False,
) -> BaseModel | str | list[BaseModel] | list[str]:
    """
    Parse LiteLLM response, with optional structured output parsing.

    Args:
        response: The response from litellm.completion/acompletion
        response_model: Optional Pydantic model for structured output

    Returns:
        If response_model is provided, returns an instance of that model (or a list when
        return_all=True). Otherwise, returns the raw string response (or list of strings).
    """
    choices = getattr(response, "choices", None) or []
    if return_all and choices:
        contents = []
        for choice in choices:
            contents.append(choice.message.content)
        return _parse_response_list(contents, response_model)

    content = choices[0].message.content if choices else ""

    finish_reason = getattr(choices[0], "finish_reason", None) if choices else None
    # When the model was truncated due to max_tokens we raise a BadRequest so downstream sees the OpenAI error.
    # Empty string responses with a truncation finish reason mean the model hit max_tokens.
    if (
        isinstance(content, str)
        and finish_reason in {"length", "token limit", "max_tokens"}
        and not content.strip()
    ):
        raise BadRequestError(
            message=(
                "OpenAIException - Could not finish the message because max_tokens or model output limit "
                "was reached. Please try again with higher max_tokens."
            ),
            llm_provider="litellm",
            model=getattr(response, "model", None),
            response=response,
            litellm_debug_info=json.dumps(
                {
                    "finish_reason": finish_reason,
                    "content_excerpt": content[:200],
                }
            ),
            body=None,
        )

    # When using structured outputs with Pydantic models, LiteLLM automatically
    # parses the response. Parse the JSON string into the Pydantic model
    if response_model is not None:
        try:
            return response_model.model_validate_json(content)
        except PydanticValidationError as exc:
            try:
                cleaned = _utils.json_to_dict(content)
                if cleaned is not None:
                    return response_model.model_validate(cleaned)
            except (
                json.JSONDecodeError,
                SyntaxError,
                TypeError,
                ValueError,
            ) as parse_exc:
                logger.debug(
                    "Structured output fallback parsing failed for %s: %s",
                    getattr(response_model, "__name__", "unknown"),
                    parse_exc,
                )
            logger.error(
                "Structured output parsing failed for %s: %s | response_snippet=%s",
                getattr(response_model, "__name__", "unknown"),
                exc,
                (content or "")[:400],
            )
            raise StructuredOutputParsingError(content=content, error=exc) from exc

    return content


def _parse_response_list(
    contents: list[str],
    response_model: type[BaseModel] | None,
) -> list[BaseModel] | list[str]:
    """
    Parse multiple LLM responses into a list of strings or Pydantic models.

    This helper is used when the LLM returns multiple choices (n>1) or when
    return_all=True. Each raw choice content is parsed independently so callers
    can score or select candidates without concatenating them into a single
    response. When response_model is provided, each content string is validated
    into that model, with a JSON-cleanup fallback that mirrors _parse_response.

    Args:
        contents: Raw choice contents (one string per LLM choice).
        response_model: Optional Pydantic model to validate each choice.

    Returns:
        List of strings when response_model is None, otherwise a list of parsed
        Pydantic model instances (one per choice).
    """
    if response_model is None:
        return contents

    parsed: list[BaseModel] = []
    for content in contents:
        try:
            parsed.append(response_model.model_validate_json(content))
        except PydanticValidationError as exc:
            try:
                cleaned = _utils.json_to_dict(content)
                if cleaned is not None:
                    parsed.append(response_model.model_validate(cleaned))
                    continue
            except (
                json.JSONDecodeError,
                SyntaxError,
                TypeError,
                ValueError,
            ):
                pass
            raise StructuredOutputParsingError(content=content, error=exc) from exc
    return parsed


# Overloads for call_model to provide precise return types
@overload
def call_model(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    response_model: None = None,
    is_reasoning: bool = False,
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
    project_name: str | None = None,
    return_all: bool = False,
) -> str | list[str]: ...


@overload
def call_model(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    response_model: type[_T] = ...,
    is_reasoning: bool = False,
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
    project_name: str | None = None,
    return_all: bool = False,
) -> _T | list[_T]: ...


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
    project_name: str | None = None,
    return_all: bool = False,
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
        project_name: Optional Opik project name override for tracing

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    _increment_llm_counter_if_in_optimizer()

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

    effective_project_name = project_name or _get_project_name_from_optimizer()

    final_params_for_litellm = _prepare_model_params(
        model_parameters,
        call_time_params,
        response_model,
        is_reasoning,
        optimization_id,
        effective_project_name,
    )

    tracked_completion = track_completion(project_name=effective_project_name)(
        litellm.completion
    )
    logger.debug(
        f"call_model: model={model} project={effective_project_name} "
        f"n={final_params_for_litellm.get('n')} has_metadata={bool(final_params_for_litellm.get('metadata'))}"
    )
    response = tracked_completion(
        model=model,
        messages=messages,
        seed=seed,
        num_retries=6,
        **_strip_project_name(final_params_for_litellm),
    )

    choices = getattr(response, "choices", None)
    logger.debug(
        f"call_model: choices={len(choices) if isinstance(choices, list) else 'unknown'}"
    )
    wants_all = return_all
    return _parse_response(response, response_model, wants_all)


# Overloads for call_model_async to provide precise return types
@overload
async def call_model_async(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    project_name: str | None = None,
    response_model: None = None,
    is_reasoning: bool = False,
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
    return_all: bool = False,
) -> str | list[str]: ...


@overload
async def call_model_async(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    project_name: str | None = None,
    response_model: type[_T] = ...,
    is_reasoning: bool = False,
    temperature: float | None = None,
    max_tokens: int | None = None,
    max_completion_tokens: int | None = None,
    top_p: float | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
    return_all: bool = False,
) -> _T | list[_T]: ...


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
    return_all: bool = False,
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
        project_name: Optional Opik project name override for tracing

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    _increment_llm_counter_if_in_optimizer()

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

    effective_project_name = project_name or _get_project_name_from_optimizer()

    final_params_for_litellm = _prepare_model_params(
        model_parameters=model_parameters,
        call_time_params=call_time_params,
        response_model=response_model,
        is_reasoning=is_reasoning,
        optimization_id=optimization_id,
        project_name=effective_project_name,
    )

    tracked_completion = track_completion(project_name=effective_project_name)(
        litellm.acompletion
    )
    logger.debug(
        f"call_model_async: model={model} project={effective_project_name} "
        f"n={final_params_for_litellm.get('n')} has_metadata={bool(final_params_for_litellm.get('metadata'))}"
    )
    response = await tracked_completion(
        model=model,
        messages=messages,
        seed=seed,
        num_retries=6,
        **_strip_project_name(final_params_for_litellm),
    )

    choices = getattr(response, "choices", None)
    logger.debug(
        f"call_model_async: choices={len(choices) if isinstance(choices, list) else 'unknown'}"
    )
    wants_all = return_all
    return _parse_response(response, response_model, wants_all)
