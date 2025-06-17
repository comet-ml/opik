import types
from typing import TypeVar, List, Any, Set
from . import opik_tracer
import logging
from opik import _logging

from google.adk.tools import agent_tool
from google.adk import agents

LOGGER = logging.getLogger(__name__)

ADKAgent = TypeVar("ADKAgent", bound=agents.BaseAgent)


class RecursiveCallbackInjector:
    def __init__(self, tracer: opik_tracer.OpikTracer) -> None:
        self._opik_tracer = tracer
        self._seen_instance_ids: Set[int] = set()

    def inject(
        self,
        root_agent: agents.BaseAgent,
    ) -> None:
        self._process_agent(root_agent)

    def _add_callbacks_to_agent(self, agent: agents.BaseAgent) -> None:
        callback_fields = {
            "before_agent_callback": self._opik_tracer.before_agent_callback,
            "after_agent_callback": self._opik_tracer.after_agent_callback,
            "before_model_callback": self._opik_tracer.before_model_callback,
            "after_model_callback": self._opik_tracer.after_model_callback,
            "before_tool_callback": self._opik_tracer.before_tool_callback,
            "after_tool_callback": self._opik_tracer.after_tool_callback,
        }

        for callback_field_name, callback_func in callback_fields.items():
            if not hasattr(agent, callback_field_name):
                continue

            current_callback_value = getattr(agent, callback_field_name)
            if current_callback_value is None:
                setattr(agent, callback_field_name, callback_func)
            elif isinstance(
                current_callback_value, list
            ) and not _contains_opik_tracer_callback(callbacks=current_callback_value):
                current_callback_value.append(callback_func)
            elif not _is_opik_callback_function(current_callback_value):
                setattr(
                    agent, callback_field_name, [current_callback_value, callback_func]
                )

    def _process_agent(
        self,
        agent: agents.BaseAgent,
    ) -> None:
        if id(agent) in self._seen_instance_ids:
            return

        self._add_callbacks_to_agent(agent)
        self._process_sub_agents(agent)
        self._process_tools(agent)

        self._seen_instance_ids.add(id(agent))

    def _process_sub_agents(
        self,
        agent: agents.BaseAgent,
    ) -> None:
        if not hasattr(agent, "sub_agents"):
            return

        for sub_agent in agent.sub_agents:
            try:
                self._process_agent(sub_agent)
            except Exception as e:
                LOGGER.warning(f"Failed to track subagent: {e}")

    def _process_tools(
        self,
        agent: agents.BaseAgent,
    ) -> None:
        if not hasattr(agent, "tools"):
            return

        for tool in agent.tools:
            if not isinstance(tool, agent_tool.AgentTool):
                continue
            try:
                self._process_agent(tool.agent)
            except Exception as e:
                LOGGER.warning(f"Failed to track agent tool: {e}")


def _is_opik_callback_function(obj: Any) -> bool:
    if not callable(obj):
        return False

    if isinstance(obj, types.MethodType):
        return isinstance(obj.__self__, opik_tracer.OpikTracer)

    return False


def _contains_opik_tracer_callback(callbacks: List) -> bool:
    return any(_is_opik_callback_function(callback) for callback in callbacks)


def track_adk_agent_recursive(
    root_agent: ADKAgent, tracer: opik_tracer.OpikTracer
) -> ADKAgent:
    """
    Recursively adds opik tracer callbacks to the agent, its subagents, and agent tools.

    Args:
        root_agent: The root ADK agent to track
        tracer: The OpikTracer instance to use for tracking

    Returns:
        The modified root agent with tracking enabled
    """
    _logging.log_once_at_level(
        logging.INFO,
        "`track_adk_agent_recursive` is experimental feature. Please let us know if something is not working as expected: https://github.com/comet-ml/opik/issues",
        logger=LOGGER,
    )
    recursive_callback_injector = RecursiveCallbackInjector(tracer)
    recursive_callback_injector.inject(root_agent)

    return root_agent
