import logging
from typing import (
    Any,
    AsyncGenerator,
    Callable,
    Dict,
    Generator,
    List,
    Optional,
    Tuple,
    Union,
)
from typing_extensions import override

from opik import llm_usage
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import SpanType, LLMProvider
from opik.api_objects import span

LOGGER = logging.getLogger(__name__)

AGENT_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    # "agent_executor",
    # "allow_delegation",
    "backstory",
    # "cache",
    # "cache_handler",
    # "crew",
    # "formatting_errors",
    "goal",
    # "i18n",
    # "id",
    # "llm",
    # "max_iter",
    # "max_rpm",
    # "max_tokens",
    "role",
    "tools",
    # "tools_handler",
    # "verbose",
]

TASK_KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    # 'agent',
    # 'async_execution',
    # 'callback',
    "config",
    "context",
    # 'converter_cls',
    # 'delegations',
    "description",
    "expected_output",
    # 'human_input',
    # 'i18n',
    # 'id',
    "name",
    # 'output',
    # 'output_file',
    # 'output_json',
    # 'output_pydantic',
    # 'processed_by_agents',
    "prompt_context",
    "tools",
    # 'tools_errors',
    # 'used_tools',
]

TASK_KWARGS_KEYS_TO_LOG_AS_OUTPUT = [
    # 'agent',
    # 'description',
    # 'expected_output',
    # 'json_dict',
    "name",
    # 'output_format',
    # 'pydantic',
    "raw",
    "summary",
]


class CrewAITrackDecorator(base_track_decorator.BaseTrackDecorator):
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
        metadata["created_from"] = "crewai"
        tags = ["crewai"]

        input_dict, name, span_type = self._parse_inputs(args, kwargs, metadata, name)

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_dict,
            type=span_type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    def _parse_inputs(
        self,
        args: Tuple,
        kwargs: Dict,
        metadata: Dict,
        name: str,
    ) -> Tuple[Dict, str, SpanType]:
        span_type: SpanType = "general"
        input_dict: Dict[str, Any] = {}

        # Crew
        if name == "kickoff":
            metadata["object_type"] = "crew"
            input_dict = kwargs.get("inputs", {})

        # Agent
        elif name == "execute_task":
            metadata["object_type"] = "agent"
            agent = args[0]
            input_dict = {"context": kwargs.get("context")}
            agent_dict = agent.model_dump(include=AGENT_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            input_dict["agent"] = agent_dict
            name = agent.role.strip()

        # Task
        elif name == "execute_sync":
            metadata["object_type"] = "task"
            input_dict = {}
            task_dict = args[0].model_dump(include=TASK_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            input_dict["task"] = task_dict
            name = f"Task: {args[0].name}"

        elif name == "completion":
            metadata["object_type"] = "completion"
            input_dict = {"messages": kwargs.get("messages")}
            span_type = "llm"
            name = "llm call"

        return input_dict, name, span_type

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        object_type = None
        metadata = {}

        if current_span_data and current_span_data.metadata:
            metadata = current_span_data.metadata
            object_type = metadata.pop("object_type")

        model, provider, output_dict, usage = self._parse_outputs(object_type, output)

        result = arguments_helpers.EndSpanParameters(
            output=output_dict,
            usage=usage,
            metadata=metadata,
            model=model,
            provider=provider,
        )

        return result

    def _parse_outputs(
        self,
        object_type: Optional[str],
        output: Any,
    ) -> Tuple[
        Optional[str],
        Optional[str],
        Dict[str, Any],
        Optional[llm_usage.OpikUsage],
    ]:
        model = None
        provider = None
        usage = None
        output_dict = {}

        if object_type == "crew":
            output_dict = output.model_dump()
            _ = output_dict.pop("token_usage", None)
        elif object_type == "agent":
            output_dict = {"output": output}
        elif object_type == "task":
            output_dict = output.model_dump(include=TASK_KWARGS_KEYS_TO_LOG_AS_OUTPUT)
        elif object_type == "completion":
            output_dict = output.model_dump()
            if output_dict.get("usage", None) is not None:
                usage = llm_usage.try_build_opik_usage_or_log_error(
                    provider=LLMProvider.OPENAI,  # even if it's not openai, we know the format is openai-like
                    usage=output_dict["usage"],
                    logger=LOGGER,
                    error_message="Failed to log token usage from CrewAI LLM call",
                )
            else:
                usage = None
            model = output_dict.pop("model", None)
            provider = (
                "openai" if output_dict.get("object") == "chat.completion" else None
            )
            output_dict = {}

        return model, provider, output_dict, usage

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._streams_handler(output, capture_output, generations_aggregator)
