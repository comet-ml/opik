import functools
from typing import Any, Dict, Optional, TYPE_CHECKING

from opik import config, opik_context

if TYPE_CHECKING:
    import litellm


def lazy_import_OpikLogger() -> Optional["litellm.integrations.opik.opik.OpikLogger"]:
    try:
        from litellm.integrations.opik.opik import OpikLogger

        return OpikLogger
    except ImportError:
        return None


def try_add_opik_monitoring_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
    if lazy_import_OpikLogger() is None:
        return params

    import litellm

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
    import litellm

    has_global_opik_logger = False
    has_local_opik_logger = False

    if OpikLoggerClass := lazy_import_OpikLogger():
        has_global_opik_logger = any(
            isinstance(callback, OpikLoggerClass) for callback in litellm.callbacks
        )

        has_local_opik_logger = any(
            isinstance(callback, OpikLoggerClass)
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


@functools.lru_cache()
def _callback_instance() -> "litellm.integrations.opik.opik.OpikLogger":  # type: ignore
    OpikLoggerClass = lazy_import_OpikLogger()
    assert OpikLoggerClass is not None
    return OpikLoggerClass()
