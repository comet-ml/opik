from typing import List, Dict

from . import nodes, subgraph_edges_builders
import google.adk.agents
import functools

CLASS_STYLES: Dict[nodes.GraphNodeType, str] = {
    nodes.GraphNodeType.SEQUENTIAL_AGENT: "fill:#d9f2d9,stroke:#339933",
    nodes.GraphNodeType.LOOP_AGENT: "fill:#e6ccff,stroke:#6600cc",
    nodes.GraphNodeType.PARALLEL_AGENT: "fill:#ffcccc,stroke:#cc0000",
    nodes.GraphNodeType.LLM_AGENT: "fill:#b3e0ff,stroke:#0077b3",
    nodes.GraphNodeType.TOOL: "fill:#ffcc99,stroke:#ff8000",
}

NODE_TYPES_TO_BUILD_SUBGRAPHS = [
    nodes.GraphNodeType.SEQUENTIAL_AGENT,
    nodes.GraphNodeType.LOOP_AGENT,
    nodes.GraphNodeType.PARALLEL_AGENT,
]


class MermaidGraphBuilder:
    def __init__(self, root_name: str):
        self.subgraphs_definitions: List[str] = []
        self.edges_definitions: List[str] = []
        self.style_definitions: List[str] = []
        self.root_name = root_name

    def build_node_graph(self, node: nodes.AgentNode) -> None:
        # 1. Create subgraph for composite nodes or process
        # connections for non-composite nodes with subagents
        if node.type in NODE_TYPES_TO_BUILD_SUBGRAPHS:
            self._build_subgraph_for_composite_node(node)
        elif len(node.subagent_nodes) > 0 or len(node.tools) > 0:
            self._build_edges_for_non_composite_llm_node(node)

        self._add_style(node.name, node.type)

        # 2. Recursively process all subagents
        for child in node.subagent_nodes:
            self.build_node_graph(child)

        # 3. Recursively process all tools
        for tool in node.tools:
            self._add_style(tool.name, nodes.GraphNodeType.TOOL)
            if tool.agent is not None:
                self.edges_definitions.append(f"{tool.name} --> {tool.agent.name}")
                self.build_node_graph(tool.agent)

    def _build_edges_for_non_composite_llm_node(self, node: nodes.AgentNode) -> None:
        for child in node.subagent_nodes:
            self.edges_definitions.append(f"{node.name} --> {child.name}")

        for tool in node.tools:
            self.edges_definitions.append(f"{node.name} --> {tool.name}")

    def _build_subgraph_for_composite_node(
        self, composite_node: nodes.AgentNode
    ) -> None:
        block = [f'subgraph {composite_node.name}["{composite_node.name}"]']

        if composite_node.type == nodes.GraphNodeType.SEQUENTIAL_AGENT:
            edge_definitions = (
                subgraph_edges_builders.build_edge_definitions_for_sequential_subagents(
                    composite_node.subagent_nodes
                )
            )
            block.extend([f"  {edge}" for edge in edge_definitions])

        elif composite_node.type == nodes.GraphNodeType.LOOP_AGENT:
            edge_definitions = (
                subgraph_edges_builders.build_edge_definitions_for_loop_subagents(
                    composite_node.subagent_nodes
                )
            )
            block.extend([f"  {edge}" for edge in edge_definitions])

        elif composite_node.type == nodes.GraphNodeType.PARALLEL_AGENT:
            edge_definitions = (
                subgraph_edges_builders.build_edge_definitions_for_parallel_subagents(
                    composite_node.subagent_nodes
                )
            )
            block.extend([f"  {edge}" for edge in edge_definitions])

        block.append("end")
        self.subgraphs_definitions.append("\n".join(block))

    @functools.lru_cache
    def _add_style(self, node_name: str, node_type: nodes.GraphNodeType) -> None:
        if node_type in CLASS_STYLES:
            self.style_definitions.append(
                f"style {node_name} {CLASS_STYLES[node_type]}"
            )

    def get_mermaid_source(self) -> str:
        return "\n".join(
            ["flowchart LR", f'{self.root_name}["{self.root_name}"]']
            + self.subgraphs_definitions
            + self.edges_definitions
            + self.style_definitions
        )


def build_mermaid_graph_definition(root_agent: google.adk.agents.BaseAgent) -> str:
    """
    Generates a Mermaid 'flowchart LR' diagram for a google-adk
    agent tree and returns the Mermaid source code.

    Args:
        root_agent (Agent):
            The root agent node of the google-adk agent tree.
            This should be an instance
            of SequentialAgent, LoopAgent, ParallelAgent or LlmAgent.

    Returns:
        str: The Mermaid source code as a string.

    Example:
        >>> mermaid_src = build_mermaid_graph_definition(my_agent_tree)
        >>> print(mermaid_src)
    """
    parsed_agent_tree = nodes.build_nodes_tree(root_agent)

    graph_builder = MermaidGraphBuilder(root_name=root_agent.name)
    graph_builder.build_node_graph(parsed_agent_tree)

    return graph_builder.get_mermaid_source()
