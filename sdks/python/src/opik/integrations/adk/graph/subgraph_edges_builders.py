from typing import List, Optional
from . import nodes
import itertools


def build_edge_definitions_for_parallel_subagents(
    children: List[nodes.AgentNode],
) -> List[str]:
    return [f'{child.name}["{child.name}"]' for child in children]


def build_edge_definitions_for_sequential_subagents(
    children: List[nodes.AgentNode],
) -> List[str]:
    if len(children) == 1:
        return [f"{children[0].name}"]

    result: List[str] = []
    for current, next in itertools.pairwise(children):
        edge_definition = f"{current.name} ==> {next.name}"
        result.append(edge_definition)

    return result


def build_edge_definitions_for_loop_subagents(
    children: List[nodes.AgentNode],
) -> List[str]:
    result = build_edge_definitions_for_sequential_subagents(children)

    loop_back_edge_definition: Optional[str] = None
    if len(children) == 1:
        loop_back_edge_definition = f"{children[0].name} ==>|repeat| {children[0].name}"
    elif len(children) > 1:
        loop_back_edge_definition = (
            f"{children[-1].name} ==>|repeat| {children[0].name}"
        )

    if loop_back_edge_definition:
        result.append(loop_back_edge_definition)

    return result
