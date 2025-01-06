import logging
from typing import Any, AsyncGenerator, Callable, Dict, Generator, List, Optional, Tuple, Union

from opik import dict_utils, opik_context
from opik.decorator import arguments_helpers, base_track_decorator

from crewai.tasks import TaskOutput


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
    "llm",
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
    'human_input',
    # 'i18n',
    # 'id',
    'name',
    # 'output',
    # 'output_file',
    # 'output_json',
    # 'output_pydantic',
    'processed_by_agents',
    'prompt_context',
    'tools',
    # 'tools_errors',
    # 'used_tools',
]



from litellm.integrations.custom_logger import CustomLogger
# from litellm.types.utils import Usage
# from crewai.agents.agent_builder.utilities.base_token_process import TokenProcess
import pprint


class OpikTokenCalcHandler(CustomLogger):
    # def __init__(self, token_cost_process: TokenProcess):
    #     self.token_cost_process = token_cost_process
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._event_kwargs = None
        self._response_obj = None
        self._start_time = None
        self._end_time = None
        self.model = None
        self.provider = None
        self.usage = None


    def log_success_event(self, kwargs, response_obj, start_time, end_time):
        try:
            print("*** CALLBACK: OpikTokenCalcHandler")
            self._event_kwargs = kwargs
            self._response_obj = response_obj
            self._start_time = start_time
            self._end_time = end_time
            self.model = response_obj.model
            self.provider = "openai" if response_obj.object == 'chat.completion' else None
            self.usage = response_obj.model_dump().get("usage")
        except Exception as e:
            print(e)


        pprint.pprint(self.usage)

        if self.usage is None:
            print("*** USAGE IS NONE!")


# def opik_callback(*args, **kwargs):
#     print("*** CALLBACK: opik_callback")


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

        if "args" in metadata:
            print()

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

            # # todo set callbacks for
            # for agent in args[0].agents:
            #     agent.step_callback = opik_callback
            #     token_usage_callback = OpikTokenCalcHandler()
            #     agent.agent_executor.callbacks = [token_usage_callback] + agent.agent_executor.callbacks
            #     # todo put this handler to metadata and use it in end_span()
            #     metadata["token_usage_callback"] = token_usage_callback
            #     # move this handler to AGENT section

            # for task in args[0].tasks:
            #     task.callback = opik_callback

        # Agent
        elif name == "execute_task":
            assert kwargs['task'].agent == args[0]
            agent = args[0]
            token_usage_callback = OpikTokenCalcHandler()
            agent.agent_executor.callbacks = [token_usage_callback] + agent.agent_executor.callbacks

            input = {}
            input["context"] = kwargs.get("context")

            # task_dict = kwargs["task"].model_dump(include=TASK_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            # input["task"] = task_dict

            agent_dict = kwargs["task"].agent.model_dump(include=AGENT_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            agent_dict["llm"] = str(agent_dict["llm"])
            input["agent"] = agent_dict

            # name = f"{name}: {input['task']['name']}"
            name = agent.role.strip()
            metadata.update({
                "object_type": "agent",
            })

        # Task
        elif name == "execute_sync":
            input = {}
            name = f"Task.{name}: {args[0].name}"
            metadata.update({
                "object_type": "task",
            })

        else:
            raise NotImplementedError

        # usage = None
        # model = None
        # provider = None

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            # model=model,
            # provider=provider,
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
            output = {"output": None}

        if current_span.metadata.get("object_type") == "agent":
            assert current_span.metadata['kwargs']['task'].agent == current_span.metadata['args'][0]
            opik_callback_handler = current_span.metadata['kwargs']['task'].agent.agent_executor.callbacks[0]
            # opik_callback_handler = current_span.metadata['args'][0].agent_executor.callbacks[0]
            # if opik_callback_handler._response_obj:
            #     usage = opik_callback_handler._response_obj.model_dump()["usage"]
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
