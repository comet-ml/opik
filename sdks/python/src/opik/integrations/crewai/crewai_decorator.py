import logging
from typing import Any, AsyncGenerator, Callable, Dict, Generator, List, Optional, Tuple, Union

from opik import dict_utils
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
            })

        tags = ["crewai"]
        # qq = str(func)

        # PARSE INPUT
        if name == "kickoff":
            input = kwargs.get("inputs")
        elif name == "execute_task":
            input = {}
            input["context"] = kwargs.get("context")

            # task_dict = kwargs["task"].model_dump()
            # task_dict2, _ = dict_utils.split_dict_by_keys(task_dict, TASK_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            task_dict = kwargs["task"].model_dump(include=TASK_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            input["task"] = task_dict

            # agent_dict = task_dict["agent"]
            # agent_dict2, _ = dict_utils.split_dict_by_keys(agent_dict, AGENT_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            agent_dict = kwargs["task"].agent.model_dump(include=AGENT_KWARGS_KEYS_TO_LOG_AS_INPUTS)
            agent_dict["llm"] = str(agent_dict["llm"])
            input["agent"] = agent_dict
            name = f"{name}: {input['task']['name']}"

        usage = None
        model = None
        provider = None

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=model,
            provider=provider,
        )

        return result

    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
    ) -> arguments_helpers.EndSpanParameters:

        usage = None
        model = None

        if not isinstance(output, str):
            output = output.model_dump()
            usage = output.pop("token_usage", None)
        else:
            output = {"output": output}

        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=usage,
            # metadata=metadata,
            model=model,
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
