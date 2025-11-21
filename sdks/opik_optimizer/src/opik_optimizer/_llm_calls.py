from typing import Any
from pydantic import BaseModel, ValidationError as PydanticValidationError
import json
import logging
import sys
from types import FrameType

import litellm
from litellm.exceptions import BadRequestError
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

from . import _throttle
from . import utils as _utils

logger = logging.getLogger(__name__)

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


def _extract_response_format_type(
    model_parameters: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    """
    Extract response_format_type from model_parameters.

    Args:
        model_parameters: Dictionary of model parameters

    Returns:
        Tuple of (response_format_type, cleaned_model_parameters)

    Raises:
        ValueError: If response_format_type has an invalid value
    """
    response_format_type = model_parameters.get("response_format_type", "native")

    valid_types = ("native", "tool_call", "prompt_injection")
    if response_format_type not in valid_types:
        raise ValueError(
            f"Invalid response_format_type: {response_format_type!r}. "
            f"Must be one of: {', '.join(valid_types)}"
        )

    # Create a cleaned copy without the response_format_type key
    cleaned_params = {
        k: v for k, v in model_parameters.items() if k != "response_format_type"
    }

    return response_format_type, cleaned_params


def _pydantic_to_tool_definition(response_model: type[BaseModel]) -> dict[str, Any]:
    """
    Convert a Pydantic model to a LiteLLM tool definition.

    Args:
        response_model: Pydantic BaseModel class

    Returns:
        Dictionary compatible with LiteLLM's tools parameter
    """
    # Get the JSON schema from the Pydantic model
    schema = response_model.model_json_schema()

    # Extract description from docstring or use a generic one
    description = (
        response_model.__doc__ or "Extract structured data from the conversation"
    )
    description = description.strip()

    return {
        "type": "function",
        "function": {
            "name": "mandatory_tool_call",
            "description": description,
            "parameters": schema,
        },
    }


def _append_json_format_instructions(
    messages: list[dict[str, str]], response_model: type[BaseModel]
) -> list[dict[str, str]]:
    """
    Append JSON format instructions to the last user message.

    Args:
        messages: List of message dictionaries
        response_model: Pydantic BaseModel class

    Returns:
        Modified messages list with format instructions appended
    """
    # Get the JSON schema from the Pydantic model
    schema = response_model.model_json_schema()
    schema_json = json.dumps(schema, indent=2)

    # Build the format instructions
    format_instructions = f"""

STRICT OUTPUT FORMAT:

- Return only the JSON value that conforms to the schema. Do not include any additional text, explanations, headings, or separators.

- Do not wrap the JSON in Markdown or code fences (no ``` or ```json).

- Do not prepend or append any text (e.g., do not write "Here is the JSON:").

- The response must be a single top-level JSON value exactly as required by the schema (object/array/etc.), with no trailing commas or comments.

The output should be formatted as a JSON instance that conforms to the JSON schema below.

As an example, for the schema {{"properties": {{"foo": {{"title": "Foo", "description": "a list of strings", "type": "array", "items": {{"type": "string"}}}}}}, "required": ["foo"]}} the object {{"foo": ["bar", "baz"]}} is a well-formatted instance of the schema. The object {{"properties": {{"foo": ["bar", "baz"]}}}} is not well-formatted.

Here is the output schema (shown in a code block for readability only — do not include any backticks or Markdown in your output):

```
{schema_json}
```"""

    # Make a copy of messages to avoid mutating the original
    modified_messages = messages.copy()

    # Find the last user message and append the instructions
    for i in range(len(modified_messages) - 1, -1, -1):
        if modified_messages[i].get("role") == "user":
            modified_messages[i] = modified_messages[i].copy()
            modified_messages[i]["content"] = (
                modified_messages[i]["content"] + format_instructions
            )
            break

    return modified_messages


def _extract_tool_call_response(response: Any) -> str:
    """
    Extract tool call arguments from LiteLLM response.

    Args:
        response: The response from litellm.completion/acompletion

    Returns:
        JSON string from the tool call arguments

    Raises:
        BadRequestError: If the tool wasn't called or response is malformed
    """
    try:
        message = response.choices[0].message

        if not hasattr(message, "tool_calls") or not message.tool_calls:
            raise BadRequestError(
                message=(
                    "Model did not return a tool call. The model may not support "
                    "function calling, or the request was malformed."
                ),
                llm_provider="litellm",
                model=getattr(response, "model", None),
                response=response,
                litellm_debug_info={"message": "No tool_calls in response"},
                body=None,
            )

        tool_call = message.tool_calls[0]

        if not hasattr(tool_call, "function") or not hasattr(
            tool_call.function, "arguments"
        ):
            raise BadRequestError(
                message="Tool call response is malformed - missing function arguments",
                llm_provider="litellm",
                model=getattr(response, "model", None),
                response=response,
                litellm_debug_info={"message": "Malformed tool_call structure"},
                body=None,
            )

        return tool_call.function.arguments

    except (AttributeError, IndexError, TypeError) as exc:
        raise BadRequestError(
            message=f"Failed to extract tool call from response: {exc}",
            llm_provider="litellm",
            model=getattr(response, "model", None),
            response=response,
            litellm_debug_info={"error": str(exc)},
            body=None,
        ) from exc


def _prepare_for_structured_outputs(
    messages: list[dict[str, str]],
    response_format_type: str,
    response_model: type[BaseModel],
) -> tuple[list[dict[str, str]], dict[str, Any]]:
    """
    Prepare messages and parameters for structured output based on strategy.

    Args:
        messages: List of message dictionaries
        response_format_type: Strategy type ("native", "tool_call", or "prompt_injection")
        response_model: Pydantic BaseModel class

    Returns:
        Tuple of (modified_messages, additional_litellm_params)
    """
    if response_format_type == "native":
        # Native response_format support
        return messages, {"response_format": response_model}

    elif response_format_type == "tool_call":
        # Use function calling with a mandatory tool
        tool_definition = _pydantic_to_tool_definition(response_model)
        return messages, {
            "tools": [tool_definition],
            "tool_choice": {
                "type": "function",
                "function": {"name": "mandatory_tool_call"},
            },
        }

    elif response_format_type == "prompt_injection":
        # Append format instructions to the prompt
        modified_messages = _append_json_format_instructions(messages, response_model)
        return modified_messages, {}

    else:
        # This should never happen due to validation in _extract_response_format_type
        raise ValueError(f"Unknown response_format_type: {response_format_type}")


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
    response_format_type: str = "native",
) -> BaseModel | str:
    """
    Parse LiteLLM response, with optional structured output parsing.

    Args:
        response: The response from litellm.completion/acompletion
        response_model: Optional Pydantic model for structured output
        response_format_type: Strategy used for structured output

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    content = response.choices[0].message.content

    finish_reason = getattr(response.choices[0], "finish_reason", None)
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
            litellm_debug_info={
                "finish_reason": finish_reason,
                "content_excerpt": content[:200],
            },
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

            # Build helpful error message based on current strategy
            error_message = (
                f"Structured output parsing failed for {getattr(response_model, '__name__', 'unknown')}: {exc}\n"
                f"Response snippet: {(content or '')[:400]}\n\n"
            )

            if response_format_type == "native":
                error_message += (
                    "Your model may not support native JSON schema response formats. "
                    "Try one of these alternatives:\n"
                    "- model_parameters={'response_format_type': 'tool_call'} if your model supports function calling\n"
                    "- model_parameters={'response_format_type': 'prompt_injection'} to inject format instructions into the prompt"
                )
            elif response_format_type == "tool_call":
                error_message += (
                    "Tool call parsing failed. "
                    "Try model_parameters={'response_format_type': 'prompt_injection'} to inject format instructions instead."
                )
            elif response_format_type == "prompt_injection":
                error_message += (
                    "Prompt injection parsing failed. The model may not be following the format instructions correctly. "
                    "Consider using a different model or adjusting your response schema."
                )

            logger.error(error_message)
            raise StructuredOutputParsingError(content=content, error=exc) from exc

    return content


@_throttle.rate_limited(_limiter)
def call_model(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    response_model: type[BaseModel] | None = None,
    is_reasoning: bool = False,
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
) -> BaseModel | str:
    """
    Call the LLM model with optional structured output.

    Args:
        messages: List of message dictionaries with 'role' and 'content' keys
        model: The model to use
        seed: Random seed for reproducibility
        model_parameters: Dictionary of model parameters. Can include 'response_format_type'
            to control structured output strategy: 'native' (default), 'tool_call', or 'prompt_injection'
        response_model: Optional Pydantic model for structured output
        is_reasoning: Flag for metadata tagging
        optimization_id: Optional ID for optimization tracking (metadata only)
        metadata: Optional metadata dict for monitoring

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    _increment_llm_counter_if_needed()

    # Build call-time params (metadata only)
    call_time_params: dict[str, Any] = {}
    if metadata is not None:
        call_time_params["metadata"] = metadata

    if model_parameters is None:
        model_parameters = {}

    # Extract response_format_type strategy before merging parameters
    response_format_type, cleaned_model_parameters = _extract_response_format_type(
        model_parameters
    )

    # Handle structured output preparation if response_model is provided
    messages_to_send = messages
    structured_output_params: dict[str, Any] = {}

    if response_model is not None:
        messages_to_send, structured_output_params = _prepare_for_structured_outputs(
            messages=messages,
            response_format_type=response_format_type,
            response_model=response_model,
        )

    final_params_for_litellm = _prepare_model_params(
        cleaned_model_parameters,
        call_time_params,
        response_model if response_format_type == "native" else None,
        is_reasoning,
        optimization_id,
    )

    # Merge structured output params (these take precedence)
    final_params_for_litellm.update(structured_output_params)

    response = litellm.completion(
        model=model,
        messages=messages_to_send,
        seed=seed,
        num_retries=6,
        **final_params_for_litellm,
    )

    # Extract content from tool call if using tool_call strategy
    if response_model is not None and response_format_type == "tool_call":
        # Replace the content with the tool call arguments for parsing
        tool_call_content = _extract_tool_call_response(response)
        response.choices[0].message.content = tool_call_content

    return _parse_response(response, response_model, response_format_type)


@_throttle.rate_limited_async(_limiter)
async def call_model_async(
    messages: list[dict[str, str]],
    model: str,
    seed: int | None = None,
    model_parameters: dict[str, Any] | None = None,
    project_name: str | None = None,
    response_model: type[BaseModel] | None = None,
    is_reasoning: bool = False,
    optimization_id: str | None = None,
    metadata: dict[str, Any] | None = None,
) -> BaseModel | str:
    """
    Async version of call_model using litellm.acompletion.

    Args:
        messages: List of message dictionaries with 'role' and 'content' keys
        model: The model to use
        seed: Random seed for reproducibility
        model_parameters: Dictionary of model parameters. Can include 'response_format_type'
            to control structured output strategy: 'native' (default), 'tool_call', or 'prompt_injection'
        project_name: Optional project name for Opik tracing
        response_model: Optional Pydantic model for structured output
        is_reasoning: Flag for metadata tagging
        optimization_id: Optional ID for optimization tracking (metadata only)
        metadata: Optional metadata dict for monitoring

    Returns:
        If response_model is provided, returns an instance of that model.
        Otherwise, returns the raw string response.
    """
    _increment_llm_counter_if_needed()

    # Build call-time params (metadata only)
    call_time_params: dict[str, Any] = {}
    if metadata is not None:
        call_time_params["metadata"] = metadata

    if model_parameters is None:
        model_parameters = {}

    # Extract response_format_type strategy before merging parameters
    response_format_type, cleaned_model_parameters = _extract_response_format_type(
        model_parameters
    )

    # Handle structured output preparation if response_model is provided
    messages_to_send = messages
    structured_output_params: dict[str, Any] = {}

    if response_model is not None:
        messages_to_send, structured_output_params = _prepare_for_structured_outputs(
            messages=messages,
            response_format_type=response_format_type,
            response_model=response_model,
        )

    final_params_for_litellm = _prepare_model_params(
        model_parameters=cleaned_model_parameters,
        call_time_params=call_time_params,
        response_model=response_model if response_format_type == "native" else None,
        is_reasoning=is_reasoning,
        optimization_id=optimization_id,
        project_name=project_name,
    )

    # Merge structured output params (these take precedence)
    final_params_for_litellm.update(structured_output_params)

    response = await litellm.acompletion(
        model=model,
        messages=messages_to_send,
        seed=seed,
        num_retries=6,
        **final_params_for_litellm,
    )

    # Extract content from tool call if using tool_call strategy
    if response_model is not None and response_format_type == "tool_call":
        # Replace the content with the tool call arguments for parsing
        tool_call_content = _extract_tool_call_response(response)
        response.choices[0].message.content = tool_call_content

    return _parse_response(response, response_model, response_format_type)
