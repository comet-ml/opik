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

from opik.types import LLMProvider
import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from openai.types import decision as openai_decision

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "questions",
]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = [
    "answers",
]


class OpenaiDecisionsTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `decisions.create` function.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "openai"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, (
            "Expected kwargs to be not None in decisions.create(**kwargs)"
        )

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_decisions",
            }
        )

        tags = ["openai"]
        model = kwargs.get("model", None)
        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        assert isinstance(output, openai_decision.Decision)

        result_dict = output.model_dump(mode="json")

        output_data, metadata = dict_utils.split_dict_by_keys(
            result_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        opik_usage = None
        if result_dict.get("usage") is not None:
            # Decisions report usage in the Responses API format, so it is parsed
            # with the OpenAI converter whatever provider the span records.
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage=result_dict["usage"],
                logger=LOGGER,
                error_message="Failed to log token usage from openai decisions call",
            )

        model = result_dict.get("model", None)

        result = arguments_helpers.EndSpanParameters(
            output=output_data,
            usage=opik_usage,
            metadata=metadata,
            model=model,
            provider=self.provider,
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        # `decisions.create` returns the full response at once; the API has no streaming mode.
        NOT_A_STREAM = None
        return NOT_A_STREAM
