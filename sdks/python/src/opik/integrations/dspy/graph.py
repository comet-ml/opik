from typing import Any
import dspy
import string

STYLES = """
classDef ReAct fill:#90EE90
classDef Predict fill:#F08080
classDef ChainOfThought fill:#ADD8E6
classDef Tools fill:##D3D3D3
"""


def build_mermaid_graph_from_module(module: dspy.Module) -> str:
    modules: dict[str, Any] = {}
    _get_dspy_module_heirarchy(module, modules)
    graph = ""
    queue = [modules]
    states: dict[str, Any] = {}
    while queue:
        current = queue.pop()
        graph += _get_mermaid_arrows(queue, current, states)

    if graph:
        for key in states:
            graph += "class {} {}\n".format(
                states[key]["state_name"], states[key]["class"]
            )
        graph += "class Tools Tools\n"

        return "graph TD\n" + graph + STYLES
    return ""


def _get_mermaid_arrows(
    queue: list[dspy.Module], current: dict[str, Any], states: dict[str, Any]
) -> str:
    start = _get_mermaid_state(current, states)
    arrows = []
    for module in current["sub_data"]:
        end = _get_mermaid_state(module, states)
        arrows.append("{} --> {}\n".format(start["text"], end["text"]))
        queue.append(module)
    if "tools" in current["details"]:
        arrows.append("{} --> Tools\n".format(start["text"]))
        arrows.append("subgraph Tools[<b>Tools</b>]\n")
        for tool in current["details"]["tools"]:
            arrows.append(f"    {tool}(<b>{tool}</b>)\n")
        arrows.append("end\n")
    return "".join(arrows)


def _get_mermaid_state(
    current: dict[str, Any], states: dict[str, Any]
) -> dict[str, Any]:
    if current["id"] not in states:
        state_name = string.ascii_uppercase[len(states)]
        state_text = "<b>%s</b>" % current["name"]
        if "instructions" in current["details"]:
            state_text += "<br><i>%s</i>" % current["details"]["instructions"][
                :100
            ].replace("`", "'")
        states[current["id"]] = {
            "state_name": state_name,
            "text": f"{state_name}({state_text})",
            "class": current["name"],
        }
    return states[current["id"]]


def _get_dspy_module_heirarchy(module: dspy.Module, data: dict[str, Any]) -> None:
    data["name"] = module.__class__.__name__
    data["id"] = id(module)
    data["details"] = {}
    if hasattr(module, "tools"):
        data["details"]["tools"] = [name for name in module.tools]
    if hasattr(module, "signature"):
        data["details"]["instructions"] = module.signature.instructions
    data["sub_data"] = []
    for name, attribute in module.__dict__.items():
        if isinstance(attribute, dspy.Module):
            sub_data: dict[str, Any] = {}
            _get_dspy_module_heirarchy(attribute, sub_data)
            data["sub_data"].append(sub_data)
        elif name == "lm":
            lm_data: dict[str, Any] = {}
            lm_data["name"] = "LM"
            lm_data["id"] = id(attribute)
            lm_data["details"] = {}
            lm_data["sub_data"] = []
            data["sub_data"].append(lm_data)
