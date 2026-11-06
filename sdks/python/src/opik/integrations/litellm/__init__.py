# This litellm integration is currently not exposed in the documentation.

try:
    from .opik_tracker import track_completion
except ModuleNotFoundError as error:
    if error.name != "litellm":
        raise
    raise ImportError(
        "The Opik LiteLLM integration requires the optional dependency. "
        "Install it with `pip install 'opik[litellm]'`."
    ) from error

__all__ = ["track_completion"]
