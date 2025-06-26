from typing import Dict, Any


def add_opik_tracer_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    from opik.integrations.langchain import OpikTracer

    opik_tracer = OpikTracer()

    if "config" not in params:
        params["config"] = {}

    if "callbacks" not in params["config"]:
        params["config"]["callbacks"] = [opik_tracer]
        return params

    callbacks = params["config"]["callbacks"]
    for callback in callbacks:
        if isinstance(callback, opik_tracer.__class__):
            return params

    callbacks.append(opik_tracer)

    return params
