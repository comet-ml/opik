from . import nodes


def build_edge_definitions_for_parallel_subagents(
    children: list[nodes.AgentNode],
) -> list[str]:
    return [f'{child.name}["{child.name}"]' for child in children]


def build_edge_definitions_for_sequential_subagents(
    children: list[nodes.AgentNode],
) -> list[str]:
    if len(children) == 1:
        return [f"{children[0].name}"]

    result: list[str] = []
    for current, next in zip(children, children[1:]):
        edge_definition = f"{current.name} ==> {next.name}"
        result.append(edge_definition)

    return result


def build_edge_definitions_for_loop_subagents(
    children: list[nodes.AgentNode],
) -> list[str]:
    result = build_edge_definitions_for_sequential_subagents(children)

    loop_back_edge_definition: str | None = None
    if len(children) == 1:
        loop_back_edge_definition = f"{children[0].name} ==>|repeat| {children[0].name}"
    elif len(children) > 1:
        loop_back_edge_definition = (
            f"{children[-1].name} ==>|repeat| {children[0].name}"
        )

    if loop_back_edge_definition:
        result.append(loop_back_edge_definition)

    return result
