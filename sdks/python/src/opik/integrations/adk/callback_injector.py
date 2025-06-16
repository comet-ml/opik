from typing import TypeVar
from . import opik_tracer
import logging
from typing import Set

from google.adk.tools import agent_tool
from google.adk import agents

LOGGER = logging.getLogger(__name__)

ADKAgent = TypeVar("ADKAgent", bound=agents.BaseAgent)


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
    LOGGER.info(
        "track_adk_agent_recursive is experimental feature. Please let us know if something is not working as expected: https://github.com/comet-ml/opik/issues"
    )
    processed_agent_instance_ids: Set[int] = set()

    _process_agent(root_agent, tracer, processed_agent_instance_ids)

    return root_agent


def _process_agent(
    agent: ADKAgent,
    tracer: opik_tracer.OpikTracer,
    processed_agent_instance_ids: Set[int],
) -> None:
    if id(agent) in processed_agent_instance_ids:
        return

    _add_callbacks_to_agent(agent, tracer)
    _process_sub_agents(agent, tracer, processed_agent_instance_ids)
    _process_tools(agent, tracer, processed_agent_instance_ids)

    processed_agent_instance_ids.add(id(agent))


def _add_callbacks_to_agent(agent: ADKAgent, tracer: opik_tracer.OpikTracer) -> None:
    callback_fields = {
        "before_agent_callback": tracer.before_agent_callback,
        "after_agent_callback": tracer.after_agent_callback,
        "before_model_callback": tracer.before_model_callback,
        "after_model_callback": tracer.after_model_callback,
        "before_tool_callback": tracer.before_tool_callback,
        "after_tool_callback": tracer.after_tool_callback,
    }

    for callback_field_name, callback_func in callback_fields.items():
        if hasattr(agent, callback_field_name):
            current_callback_value = getattr(agent, callback_field_name)
            if current_callback_value is None:
                setattr(agent, callback_field_name, callback_func)
            elif isinstance(current_callback_value, list):
                current_callback_value.append(callback_func)
            else:
                setattr(
                    agent, callback_field_name, [current_callback_value, callback_func]
                )


def _process_sub_agents(
    agent: ADKAgent,
    tracer: opik_tracer.OpikTracer,
    processed_agent_instance_ids: Set[int],
) -> None:
    if not hasattr(agent, "sub_agents"):
        return

    for sub_agent in agent.sub_agents:
        try:
            _process_agent(sub_agent, tracer, processed_agent_instance_ids)
        except Exception as e:
            LOGGER.warning(f"Failed to track subagent: {e}")


def _process_tools(
    agent: ADKAgent,
    tracer: opik_tracer.OpikTracer,
    processed_agent_instance_ids: Set[int],
) -> None:
    if not hasattr(agent, "tools"):
        return

    for tool in agent.tools:
        if not isinstance(tool, agent_tool.AgentTool):
            continue
        try:
            _process_agent(tool.agent, tracer, processed_agent_instance_ids)
        except Exception as e:
            LOGGER.warning(f"Failed to track agent tool: {e}")
