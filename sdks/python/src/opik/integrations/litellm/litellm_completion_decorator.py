import logging
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Tuple,
    Union,
)
from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

import litellm
import litellm.types.utils
import litellm.litellm_core_utils.streaming_handler

from . import stream_patchers, completion_chunks_aggregator

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS: List[str] = [
    "messages",
    "functions",
    "function_call",
    "tools",
    "tool_choice",
    "response_format",
    "stop",
]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT: List[str] = ["choices"]

# Sensitive parameters that should never be logged
SENSITIVE_PARAMS_TO_EXCLUDE: List[str] = [
    "api_key",
    "aws_access_key_id",
    "aws_secret_access_key",
    "azure_ad_token",
    "azure_ad_token_provider",
    "azure_key",
    "azure_password",
    "azure_username",
    "client_secret",
    "vertex_credentials",
    "vertex_project",
    "vertex_location",
    "anthropic_api_key",
    "openai_api_key",
    "cohere_api_key",
    "replicate_api_key",
    "huggingface_api_key",
    "togetherai_api_key",
    "baseten_api_key",
    "openrouter_api_key",
]

PROVIDER_MAPPING: Dict[str, LLMProvider] = {
    "openai": LLMProvider.OPENAI,
    "vertex_ai": LLMProvider.GOOGLE_VERTEXAI,
    "vertex_ai-language-models": LLMProvider.GOOGLE_VERTEXAI,
    "gemini": LLMProvider.GOOGLE_AI,
    "anthropic": LLMProvider.ANTHROPIC,
    "vertex_ai-anthropic_models": LLMProvider.ANTHROPIC_VERTEXAI,
    "bedrock": LLMProvider.BEDROCK,
    "bedrock_converse": LLMProvider.BEDROCK,
    "groq": LLMProvider.GROQ,
}


def _extract_provider_from_model(model_name: str) -> Optional[LLMProvider]:
    try:
        provider_info = litellm.get_llm_provider(model_name)
        provider_name = provider_info[1] if len(provider_info) > 1 else None
        if provider_name is None:
            return None
        return PROVIDER_MAPPING.get(provider_name, None)
    except Exception:
        return None


def _convert_response_to_dict(
    output: Union[
        litellm.types.utils.ModelResponse,
        Dict[str, Any],
    ],
) -> Dict[str, Any]:
    if hasattr(output, "model_dump"):
        return output.model_dump(mode="json")
    elif isinstance(output, dict):
        return output
    else:
        return dict(output)


def _extract_usage_from_response(
    response_dict: Dict[str, Any],
) -> Optional[llm_usage.OpikUsage]:
    usage_data = response_dict.get("usage")
    if usage_data is None:
        return None

    opik_usage = llm_usage.try_build_opik_usage_or_log_error(
        provider=LLMProvider.OPENAI,
        usage=usage_data,
        logger=LOGGER,
        error_message="Failed to log token usage from litellm call",
    )

    if opik_usage is None:
        opik_usage = llm_usage.build_opik_usage_from_unknown_provider(
            usage=usage_data,
        )

    return opik_usage


def _calculate_completion_cost(
    output: Union[
        litellm.types.utils.ModelResponse,
        Dict[str, Any],
    ],
) -> Optional[float]:
    try:
        return litellm.completion_cost(completion_response=output)
    except Exception as exception:
        LOGGER.debug(
            "Failed to calculate cost from litellm response: %s",
            str(exception),
            exc_info=True,
        )
        return None


class LiteLLMCompletionTrackDecorator(base_track_decorator.BaseTrackDecorator):
    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}

        # Filter out sensitive parameters before logging
        filtered_kwargs = {
            key: value
            for key, value in kwargs.items()
            if key not in SENSITIVE_PARAMS_TO_EXCLUDE
        }

        input_data, new_metadata = dict_utils.split_dict_by_keys(
            filtered_kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata["created_from"] = "litellm"

        model_name = kwargs.get("model", "")
        provider = _extract_provider_from_model(model_name)

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input_data,
            type=track_options.type,
            tags=["litellm"],
            metadata=metadata,
            project_name=track_options.project_name,
            model=model_name,
            provider=provider,
        )

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
                dict,
            ),
        ), f"Expected ModelResponse or dict, got {type(output)}"

        response_dict = _convert_response_to_dict(output)
        output_data, metadata = dict_utils.split_dict_by_keys(
            response_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        model = response_dict.get("model")
        provider = _extract_provider_from_model(model) if model else None
        opik_usage = _extract_usage_from_response(response_dict)
        total_cost = _calculate_completion_cost(output)

        return arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=opik_usage,
            metadata=metadata,
            model=model,
            provider=provider.value if provider else None,
            total_cost=total_cost,
        )

    @override
    def _streams_handler(  # type: ignore
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[
            Callable[
                [List[litellm.types.utils.ModelResponse]],
                Optional[litellm.types.utils.ModelResponse],
            ]
        ],
    ) -> Optional[litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper]:
        assert (
            generations_aggregator is not None
        ), "LiteLLM decorator will always get aggregator function as input"

        is_litellm_stream = isinstance(
            output, litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper
        )
        if not is_litellm_stream:
            return None

        span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()

        return stream_patchers.patch_stream(
            stream=output,
            span_to_end=span_to_end,
            trace_to_end=trace_to_end,
            generations_aggregator=completion_chunks_aggregator.aggregate,
            finally_callback=self._after_call,
        )
