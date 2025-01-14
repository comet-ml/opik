import functools
from typing import Any, Dict

import litellm
from litellm.integrations.opik import opik as litellm_opik_logger

from opik import opik_context


def add_opik_monitoring_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    params = _add_span_metadata_to_params(params)
    params = _add_callback_to_params(params)
    return params


def _add_span_metadata_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    current_span = opik_context.get_current_span_data()

    if current_span is None:
        return params

    if "current_span_data" in params.get("metadata", {}).get("opik", {}):
        return params

    return {
        **params,
        "metadata": {
            **params.get("metadata", {}),
            "opik": {
                **params.get("metadata", {}).get("opik", {}),
                "current_span_data": current_span,
                "project_name": current_span.project_name,
            },
        },
    }


def _add_callback_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    has_global_opik_logger = any(
        isinstance(callback, litellm_opik_logger.OpikLogger)
        for callback in litellm.callbacks
    )

    has_local_opik_logger = any(
        isinstance(callback, litellm_opik_logger.OpikLogger)
        for callback in params.get("success_callback", [])
    )

    if has_global_opik_logger or has_local_opik_logger:
        return params

    opik_logger = _callback_instance()

    return {
        **params,
        "success_callback": [opik_logger, *params.get("success_callback", [])],
        "failure_callback": [opik_logger, *params.get("error_callback", [])],
    }


@functools.lru_cache
def _callback_instance() -> litellm_opik_logger.OpikLogger:
    return litellm_opik_logger.OpikLogger()
