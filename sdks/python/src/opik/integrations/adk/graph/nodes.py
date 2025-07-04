from typing import List, Optional, Literal, Any

import google.adk.agents
import google.adk.tools.agent_tool
import inspect

AgentNodeType = Literal["sequential", "loop", "parallel", "llm"]

SUBGRAPH_NODE_TYPES: List[AgentNodeType] = ["sequential", "loop", "parallel"]


class AgentNode:
    def __init__(self, agent: google.adk.agents.BaseAgent) -> None:
        self.agent = agent
        self.name = agent.name
        self.children_nodes: List[AgentNode] = []
        self.agent_type = _determine_agent_type(agent)
        self.tools: List[ToolNode] = []

    def add_child(self, child: "AgentNode") -> None:
        self.children_nodes.append(child)

    def add_tool(self, tool: "ToolNode") -> None:
        self.tools.append(tool)

    @property
    def first_child_name(self) -> Optional[str]:
        return self.children_nodes[0].name if self.children_nodes else None

    @property
    def last_child_name(self) -> Optional[str]:
        return self.children_nodes[-1].name if self.children_nodes else None


class ToolNode:
    def __init__(self, tool: Any, name: str) -> None:
        self.tool = tool
        self.name = name
        self.agent: Optional[AgentNode] = None

    def associate_agent_node(self, agent: AgentNode) -> None:
        """Used to link a tool node with agent node if a tool is an agent"""
        self.agent = agent


def _determine_agent_type(agent: google.adk.agents.BaseAgent) -> AgentNodeType:
    if isinstance(agent, google.adk.agents.SequentialAgent):
        return "sequential"
    elif isinstance(agent, google.adk.agents.LoopAgent):
        return "loop"
    elif isinstance(agent, google.adk.agents.ParallelAgent):
        return "parallel"
    else:
        return "llm"


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
    if hasattr(tool, "name") and isinstance(tool.name, str):
        return tool.name

    if inspect.isfunction(tool):
        return tool.__name__

    if hasattr(tool, "__class__"):
        return tool.__class__.__name__

    return str(tool)
