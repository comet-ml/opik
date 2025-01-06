import logging
from typing import Any, AsyncGenerator, Callable, Dict, Generator, List, Optional, Tuple, Union

from crewai.tasks import TaskOutput

from opik import opik_context
from opik.decorator import arguments_helpers, base_track_decorator

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
    'config',
    'context',
    # 'converter_cls',
    # 'delegations',
    'description',
    'expected_output',
    # 'human_input',
    # 'i18n',
    # 'id',
    'name',
    # 'output',
    # 'output_file',
    # 'output_json',
    # 'output_pydantic',
    # 'processed_by_agents',
    'prompt_context',
    'tools',
    # 'tools_errors',
    # 'used_tools',
]



from litellm.integrations.custom_logger import CustomLogger


class OpikTokenCalcHandler(CustomLogger):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._event_kwargs = None
        self._response_obj = None
        self.model = None
        self.provider = None
        self.usage = None


    def log_success_event(self, kwargs, response_obj, start_time, end_time):
        self._event_kwargs = kwargs
        self._response_obj = response_obj
        self.model = response_obj.model
        self.provider = "openai" if response_obj.object == 'chat.completion' else None
        self.usage = response_obj.model_dump().get("usage")


class CrewAITrackDecorator(base_track_decorator.BaseTrackDecorator):
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:

        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}

        metadata.update({
                "created_from": "crewai",
                "args": args,
                "kwargs": kwargs,
            })

        tags = ["crewai"]

        ######################
        # PARSE INPUT
        ######################
        # Crew
        if name == "kickoff":
            input = kwargs.get("inputs")
            metadata.update({
                "object_type": "crew",
            })

        # Agent
        elif name == "execute_task":
            assert kwargs['task'].agent == args[0]
            agent = args[0]
            token_usage_callback = OpikTokenCalcHandler()
            agent.agent_executor.callbacks = [token_usage_callback] + agent.agent_executor.callbacks

            input = {}
            input["context"] = kwargs.get("context")
            agent_dict = agent.model_dump(include=AGENT_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            input["agent"] = agent_dict

            name = agent.role.strip()
            metadata.update({
                "object_type": "agent",
            })

        # Task
        elif name == "execute_sync":
            input = {}
            task_dict = args[0].model_dump(include=TASK_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            input["task"] = task_dict

            name = f"Task: {args[0].name}"
            metadata.update({
                "object_type": "task",
            })

        else:
            raise NotImplementedError

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
    ) -> arguments_helpers.EndSpanParameters:

        usage = None
        model = None
        provider = None

        current_span = opik_context.get_current_span_data()

        if isinstance(output, TaskOutput):
            output = {"output": output.raw}
        elif isinstance(output, str):
            output = {"output": output}
        else:
            # output = output.model_dump()
            # usage = output.pop("token_usage", None)
            output = {}

        if current_span.metadata.get("object_type") == "agent":
            opik_callback_handler = current_span.metadata['args'][0].agent_executor.callbacks[0]
            if opik_callback_handler:
                model = opik_callback_handler.model
                provider = opik_callback_handler.provider
                usage = opik_callback_handler.usage

        metadata = current_span.metadata

        metadata.pop('args')
        metadata.pop('kwargs')
        metadata.pop('token_usage_callback', None)

        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=usage,
            metadata=metadata,
            model=model,
            provider=provider,
        )

        return result

    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._generators_handler(
            output, capture_output, generations_aggregator
        )
