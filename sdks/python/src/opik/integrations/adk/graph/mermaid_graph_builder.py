from typing import List

from . import nodes, subgraph_edges_builders
import google.adk.agents


class MermaidGraphBuilder:
    def __init__(self, root_name: str):
        self.subgraphs_definitions: List[str] = []
        self.edges_definitions: List[str] = []
        self.root_name = root_name

    def build_node_graph(self, node: nodes.AgentNode) -> None:
        # 1. Create subgraph for composite nodes or process
        # connections for non-composite nodes with children
        if node.agent_type in nodes.SUBGRAPH_NODE_TYPES:
            self._build_subgraph_for_composite_node(node)
        elif len(node.children_nodes) > 0 or len(node.tools) > 0:
            self._build_edges_for_non_composite_llm_node(node)

        # 2. Recursively process all children agents
        for child in node.children_nodes:
            self.build_node_graph(child)

        # 3. Recursively process all agent tools
        for tool in node.tools:
            if tool.agent is not None:
                self.build_node_graph(tool.agent)

    def _build_edges_for_non_composite_llm_node(self, node: nodes.AgentNode) -> None:
        for child in node.children_nodes:
            self.edges_definitions.append(f"{node.name} --> {child.name}")

        for tool in node.tools:
            self.edges_definitions.append(f"{node.name} --> {tool.name}")

    def _build_subgraph_for_composite_node(
        self, composite_node: nodes.AgentNode
    ) -> None:
        block = [f'subgraph {composite_node.name}["{composite_node.name}"]']

        if composite_node.agent_type == "sequential":
            edge_definitions = (
                subgraph_edges_builders.build_edge_definitions_for_sequential_children(
                    composite_node.children_nodes
                )
            )
            block.extend([f"  {edge}" for edge in edge_definitions])

        elif composite_node.agent_type == "loop":
            edge_definitions = (
                subgraph_edges_builders.build_edge_definitions_for_loop_children(
                    composite_node.children_nodes
                )
            )
            block.extend([f"  {edge}" for edge in edge_definitions])

        elif composite_node.agent_type == "parallel":
            edge_definitions = (
                subgraph_edges_builders.build_edge_definitions_for_parallel_children(
                    composite_node.children_nodes
                )
            )
            block.extend([f"  {edge}" for edge in edge_definitions])

        block.append("end")
        self.subgraphs_definitions.append("\n".join(block))

    def get_mermaid_source(self) -> str:
        if not self.root_name:
            raise ValueError("Root name must be set before generating Mermaid source")

        return "\n".join(
            ["flowchart LR", f'{self.root_name}["{self.root_name}"]']
            + self.subgraphs_definitions
            + self.edges_definitions
        )


def build_mermaid(root_agent: google.adk.agents.BaseAgent) -> str:
    """
    Generates a Mermaid 'flowchart LR' diagram for a google-adk
    agent tree and returns the Mermaid source code.

    Args:
        root_agent (Agent):
            The root agent node of the google-adk agent tree.
            This should be an instance
            of SequentialAgent, LoopAgent, ParallelAgent,
            or a compatible agent class with a
            `name` attribute and an optional `sub_agents`
            attribute.

    Returns:
        str: The Mermaid source code as a string.

    Example:
        >>> mermaid_src = build_mermaid(my_agent_tree)
        >>> print(mermaid_src)
    """
    parsed_agent_tree = nodes.build_nodes_tree(root_agent)

    graph_builder = MermaidGraphBuilder(root_name=root_agent.name)
    graph_builder.build_node_graph(parsed_agent_tree)

    return graph_builder.get_mermaid_source()
