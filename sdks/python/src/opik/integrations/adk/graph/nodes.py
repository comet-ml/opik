from typing import List, Optional, Any
from enum import Enum

import google.adk.agents
import google.adk.tools.agent_tool
import inspect


class GraphNodeType(str, Enum):
    SEQUENTIAL_AGENT = "sequential_agent"
    LOOP_AGENT = "loop_agent"
    PARALLEL_AGENT = "parallel_agent"
    LLM_AGENT = "llm_agent"
    TOOL = "tool"


class AgentNode:
    def __init__(self, agent: google.adk.agents.BaseAgent) -> None:
        self.agent = agent
        self.name = _to_mermaid_compatible_name(agent.name)
        self.subagent_nodes: List[AgentNode] = []
        self.type = _determine_agent_type(agent)
        self.tools: List[ToolNode] = []

    def add_child(self, child: "AgentNode") -> None:
        self.subagent_nodes.append(child)

    def add_tool(self, tool: "ToolNode") -> None:
        self.tools.append(tool)

    @property
    def first_child_name(self) -> Optional[str]:
        return self.subagent_nodes[0].name if self.subagent_nodes else None

    @property
    def last_child_name(self) -> Optional[str]:
        return self.subagent_nodes[-1].name if self.subagent_nodes else None


class ToolNode:
    def __init__(self, tool: Any, name: str) -> None:
        self.tool = tool
        self.name = name
        self.agent: Optional[AgentNode] = None
        self.type = GraphNodeType.TOOL

    def associate_agent_node(self, agent: AgentNode) -> None:
        """Used to link a tool node with agent node if a tool is an agent"""
        self.agent = agent


def _determine_agent_type(agent: google.adk.agents.BaseAgent) -> GraphNodeType:
    if isinstance(agent, google.adk.agents.SequentialAgent):
        return GraphNodeType.SEQUENTIAL_AGENT
    elif isinstance(agent, google.adk.agents.LoopAgent):
        return GraphNodeType.LOOP_AGENT
    elif isinstance(agent, google.adk.agents.ParallelAgent):
        return GraphNodeType.PARALLEL_AGENT
    else:
        return GraphNodeType.LLM_AGENT


def _to_mermaid_compatible_name(name: str) -> str:
    return name.replace(" ", "_")


def build_nodes_tree(agent: google.adk.agents.BaseAgent) -> AgentNode:
    node = AgentNode(agent)

    sub_agents = getattr(agent, "sub_agents", []) or []

    for sub_agent in sub_agents:
        child_node = build_nodes_tree(sub_agent)
        node.add_child(child_node)

    tools = getattr(agent, "tools", [])
    for tool in tools:
        tool_node = ToolNode(tool, name=_extract_tool_name(tool))
        if isinstance(tool, google.adk.tools.agent_tool.AgentTool):
            tool_agent_node = build_nodes_tree(tool.agent)
            tool_node.associate_agent_node(tool_agent_node)

        node.add_tool(tool_node)

    return node


def _extract_tool_name(tool: Any) -> str:
    if isinstance(tool, google.adk.tools.agent_tool.AgentTool):
        return _to_mermaid_compatible_name(f"AgentTool:{tool.name}")

    if inspect.isfunction(tool):
        return tool.__name__

    if hasattr(tool, "name") and isinstance(tool.name, str):
        return _to_mermaid_compatible_name(tool.name)

    if hasattr(tool, "__class__"):
        return tool.__class__.__name__

    return _to_mermaid_compatible_name(str(tool))
