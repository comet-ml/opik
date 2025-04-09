import functools
from typing import Any, Dict

import litellm

try:
    from litellm.integrations.opik import opik as litellm_opik_logger
except ImportError:
    litellm_opik_logger = None


from opik import opik_context
from opik import config


def try_add_opik_monitoring_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    if litellm_opik_logger is None:
        return params

    already_decorated = hasattr(litellm.completion, "opik_tracked")
    if already_decorated:
        return params

    params = _add_span_metadata_to_params(params)
    params = _ensure_params_have_callback(params)
    return params


@functools.lru_cache
def enabled_in_config() -> bool:
    config_ = config.OpikConfig()
    return config_.enable_litellm_models_monitoring


@functools.lru_cache
def opik_is_misconfigured() -> bool:
    config_ = config.OpikConfig()
    return config_.check_for_known_misconfigurations()


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


def _ensure_params_have_callback(params: Dict[str, Any]) -> Dict[str, Any]:
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
def _callback_instance() -> "litellm_opik_logger.OpikLogger":  # type: ignore
    return litellm_opik_logger.OpikLogger()
