from typing import List, Optional, Literal

import google.adk.agents

AgentNodeType = Literal["sequential", "loop", "parallel", "llm"]

SUBGRAPH_NODE_TYPES: List[AgentNodeType] = ["sequential", "loop", "parallel"]


class AgentNode:
    def __init__(self, agent: google.adk.agents.BaseAgent) -> None:
        self.agent = agent
        self.name = agent.name
        self.children_nodes: List[AgentNode] = []
        self.agent_type = _determine_agent_type(agent)

    def add_child(self, child: "AgentNode") -> None:
        self.children_nodes.append(child)

    @property
    def first_child_name(self) -> Optional[str]:
        return self.children_nodes[0].name if self.children_nodes else None

    @property
    def last_child_name(self) -> Optional[str]:
        return self.children_nodes[-1].name if self.children_nodes else None


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

    return node
