import logging
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Tuple,
)

from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

import litellm
import litellm.types.utils

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "messages",
    "functions",
    "function_call",
    "tools",
    "tool_choice",
]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["choices"]


class LiteLLMCompletionTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of LiteLLM's `completion` and `acompletion` functions.

    Besides special processing for input arguments and response content, it
    overrides _streams_handler() method to work correctly with
    LiteLLM CustomStreamWrapper objects.
    """

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in litellm.completion(**kwargs) or litellm.acompletion(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "litellm",
            }
        )

        tags = ["litellm"]

        model_name = kwargs.get("model", "")
        provider = _get_provider_from_model(model_name)

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model_name,
            provider=provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(
            output,
            (
                litellm.types.utils.ModelResponse,
                dict,  # For dict responses
            ),
        ), f"Expected ModelResponse or dict, got {type(output)}"

        # Handle both ModelResponse objects and dict responses
        if hasattr(output, "model_dump"):
            result_dict = output.model_dump(mode="json")
        elif isinstance(output, dict):
            result_dict = output
        else:
            result_dict = dict(output)

        output_data, metadata = dict_utils.split_dict_by_keys(
            result_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        opik_usage = None
        if result_dict.get("usage") is not None:
            model_name = result_dict.get("model", "")
            provider = _get_provider_from_model(model_name)

            if provider is not None:
                opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                    provider=provider,
                    usage=result_dict["usage"],
                    logger=LOGGER,
                    error_message="Failed to log token usage from litellm call",
                )
            else:
                opik_usage = llm_usage.build_opik_usage_from_unknown_provider(
                    usage=result_dict["usage"],
                )

        model = result_dict.get("model")

        result = arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=opik_usage,
            metadata=metadata,
            model=model,
        )

        return result

    @override
    def _streams_handler(  # type: ignore
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        # Streaming is not currently supported for LiteLLM

        NOT_A_STREAM = None
        return NOT_A_STREAM


def _get_provider_from_model(model_name: str) -> Optional[LLMProvider]:
    """
    Extract the actual provider from the model name using LiteLLM's built-in method.
    """
    try:
        provider_info = litellm.get_llm_provider(model_name)
        provider_name = provider_info[1] if len(provider_info) > 1 else None

        # Map LiteLLM provider names to our LLMProvider enum
        # Based on mapping from Java backend CostService.java to ensure consistency
        provider_mapping = {
            "openai": LLMProvider.OPENAI,
            "vertex_ai-language-models": LLMProvider.GOOGLE_VERTEXAI,
            "gemini": LLMProvider.GOOGLE_AI,
            "anthropic": LLMProvider.ANTHROPIC,
            "vertex_ai-anthropic_models": LLMProvider.ANTHROPIC_VERTEXAI,
            "bedrock": LLMProvider.BEDROCK,
            "bedrock_converse": LLMProvider.BEDROCK,
            "groq": LLMProvider.GROQ,
        }

        return provider_mapping.get(provider_name, None)
    except Exception:
        return None
