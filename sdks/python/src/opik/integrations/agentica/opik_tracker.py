import functools
from typing import Any, Dict, Optional

import opik
from opik import opik_context

_SYMBOLICA_PROVIDER = "symbolica"


def track_agentica(project_name: Optional[str] = None) -> None:
    """Enable Opik tracking for Agentica's ``Agent.call`` method globally.

    This patches ``agentica.Agent.call`` and ``agentica.Agent.__call__`` once.
    All future Agentica calls are captured as LLM spans/traces.
    """
    import agentica

    if (
        hasattr(agentica.Agent.call, "opik_tracked")
        and agentica.Agent.call.opik_tracked
    ):  # type: ignore[attr-defined]
        return

    original_call = agentica.Agent.call

    @functools.wraps(original_call)
    async def tracked_call(*args: Any, **kwargs: Any) -> Any:
        result = await original_call(*args, **kwargs)

        agent = args[0] if len(args) > 0 else None
        if agent is not None:
            _update_span_with_agentica_usage(agent)

        return result

    decorator = opik.track(
        name="agentica_call",
        type="llm",
        tags=["agentica", "symbolica"],
        metadata={"created_from": "agentica"},
        project_name=project_name,
    )
    patched_call = decorator(tracked_call)

    agentica.Agent.call = patched_call
    # Keep legacy invocation style (`await agent(...)`) tracked as well.
    agentica.Agent.__call__ = patched_call


def _update_span_with_agentica_usage(agent: Any) -> None:
    usage = _extract_usage_for_opik(agent)
    model = _extract_model(agent)

    update_kwargs: Dict[str, Any] = {"provider": _SYMBOLICA_PROVIDER}
    if usage is not None:
        update_kwargs["usage"] = usage
    if model is not None:
        update_kwargs["model"] = model

    try:
        opik_context.update_current_span(**update_kwargs)
    except Exception:
        # Do not fail user code if usage/model enrichment fails.
        pass


def _extract_model(agent: Any) -> Optional[str]:
    model = getattr(agent, "_Agent__model", None)
    return model if isinstance(model, str) else None


def _extract_usage_for_opik(agent: Any) -> Optional[Dict[str, int]]:
    if not hasattr(agent, "last_usage"):
        return None

    try:
        usage_object = agent.last_usage()
    except Exception:
        return None

    usage_raw = _usage_object_to_dict(usage_object)
    if usage_raw is None:
        return None

    return _normalize_usage_for_opik(usage_raw)


def _usage_object_to_dict(usage_object: Any) -> Optional[Dict[str, Any]]:
    if usage_object is None:
        return None
    if isinstance(usage_object, dict):
        return usage_object

    model_dump = getattr(usage_object, "model_dump", None)
    if callable(model_dump):
        result = model_dump(exclude_none=True)
        return result if isinstance(result, dict) else None

    to_dict = getattr(usage_object, "dict", None)
    if callable(to_dict):
        result = to_dict()
        return result if isinstance(result, dict) else None

    return None


def _normalize_usage_for_opik(usage: Dict[str, Any]) -> Optional[Dict[str, int]]:
    prompt_tokens = _extract_number(usage, ("prompt_tokens", "input_tokens"))
    completion_tokens = _extract_number(usage, ("completion_tokens", "output_tokens"))
    total_tokens = _extract_number(usage, ("total_tokens",))

    if (
        total_tokens is None
        and prompt_tokens is not None
        and completion_tokens is not None
    ):
        total_tokens = prompt_tokens + completion_tokens

    normalized: Dict[str, int] = {}
    if prompt_tokens is not None:
        normalized["prompt_tokens"] = prompt_tokens
    if completion_tokens is not None:
        normalized["completion_tokens"] = completion_tokens
    if total_tokens is not None:
        normalized["total_tokens"] = total_tokens

    flattened_original_usage: Dict[str, int] = {}
    _flatten_numeric_values(usage, "original_usage", flattened_original_usage)
    normalized.update(flattened_original_usage)

    return normalized if len(normalized) > 0 else None


def _extract_number(container: Dict[str, Any], keys: tuple[str, ...]) -> Optional[int]:
    for key in keys:
        value = container.get(key)
        if isinstance(value, bool):
            continue
        if isinstance(value, (int, float)):
            return int(value)

    return None


def _flatten_numeric_values(
    value: Any,
    key_prefix: str,
    output: Dict[str, int],
) -> None:
    if isinstance(value, dict):
        for key, nested_value in value.items():
            next_key = f"{key_prefix}.{key}"
            _flatten_numeric_values(nested_value, next_key, output)
        return

    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)):
        output[key_prefix] = int(value)
