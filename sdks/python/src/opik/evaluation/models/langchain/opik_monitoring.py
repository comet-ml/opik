import functools
from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from opik.integrations.langchain import opik_tracer


@functools.lru_cache
def _get_opik_tracer() -> "opik_tracer.OpikTracer":
    from opik.integrations.langchain import OpikTracer

    return OpikTracer()


def add_opik_tracer_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    if "config" not in params:
        params["config"] = {}

    opik_tracer_ = _get_opik_tracer()

    if "callbacks" not in params["config"]:
        params["config"]["callbacks"] = [opik_tracer_]
        return params

    callbacks = params["config"]["callbacks"]
    for callback in callbacks:
        if isinstance(callback, opik_tracer_.__class__):
            return params

    callbacks.append(opik_tracer_)

    return params
