from typing import Any, Dict, Optional, Union
import dspy
import string

STYLES = """
classDef ReAct fill:#90EE90
classDef Predict fill:#F08080
classDef ChainOfThought fill:#ADD8E6
classDef Tools fill:##D3D3D3
"""

def get_mermaid_graph(module: dspy.Module) -> str:
    modules = {}
    get_dspy_module_heirarchy(module, modules)
    graph = ""
    queue = [modules]
    states = {}
    while queue:
        current = queue.pop()
        graph += get_mermaid_arrows(queue, current, states)

    if graph:
        for key in states:
            graph += "class %s %s\n" % (states[key]["state_name"], states[key]["class"])
        graph += "class Tools Tools\n"

        return "graph TD\n" + graph + STYLES

def get_mermaid_arrows(queue: List[dspy.Modules], current: Dict[str, Any], states: Dict[str, Any]):
    start = get_mermaid_state(current, states)
    arrows = []
    for module in current["sub_data"]:
        end = get_mermaid_state(module, states)
        arrows.append("%s --> %s\n" % (start["text"], end["text"]))
        queue.append(module)
    if "tools" in current["details"]:
        arrows.append("%s --> Tools\n" % (start["text"],))
        arrows.append("subgraph Tools[<b>Tools</b>]\n")
        for tool in current["details"]["tools"]:
            arrows.append("    %s(<b>%s</b>)\n" % (tool, tool))
        arrows.append("end\n")
    return "".join(arrows)

def get_mermaid_state(current: Dict[str, Any], states: Dict[str, Any]):
    if current["id"] not in states:
        state_name = string.ascii_uppercase[len(states)]
        state_text = "<b>%s</b>" % current["name"]
        if "instructions" in current["details"]:
            state_text += "<br><i>%s</i>" % current["details"]["instructions"][:100].replace("`", "'")
        states[current["id"]] = {
            "state_name": state_name,
            "text": "%s(%s)" % (state_name, state_text),
            "class": current["name"],
        }
    return states[current["id"]]

def get_dspy_module_heirarchy(module: dspy.Module, data: Dict[str, Any]):
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
            sub_data = {}
            get_dspy_module_heirarchy(attribute, sub_data)
            data["sub_data"].append(sub_data)
        elif name == "lm":
            sub_data = {}
            sub_data["name"] = "LM"
            sub_data["id"] = id(attribute)
            sub_data["details"] = {}
            sub_data["sub_data"] = []
            data["sub_data"].append(sub_data)

