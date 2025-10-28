"""
Helpers that describe capabilities supported by evaluation models.

Currently we focus on vision (image input) support so prompt rendering can
either preserve multimodal content or gracefully degrade to text-only prompts.
"""

from typing import Optional, Set

# Known families or exact identifiers for vision-capable models. The list can be
# extended at runtime via :func:`add_vision_model`.
_VISION_MODELS: Set[str] = {
    # OpenAI
    "gpt-4-vision",
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-4-turbo",
    "chatgpt-4o-latest",
    # Anthropic
    "claude-3",
    "claude-3-5",
    # Google
    "gemini-1.5-pro",
    "gemini-1.5-flash",
    "gemini-pro-vision",
    "gemini-2.0-flash",
    # Meta
    "llama-3.2-11b-vision",
    "llama-3.2-90b-vision",
    # Mistral
    "pixtral",
    # Misc
    "qwen-vl",
    "qwen2-vl",
    "phi-3-vision",
    "phi-3.5-vision",
    "llava",
    "cogvlm",
    "yi-vl",
}


def supports_vision(model_name: Optional[str]) -> bool:
    """
    Return True when the supplied model name is known to accept image inputs.
    """
    if not model_name:
        return False

    # Try the detection helper from LiteLLM if available.
    try:
        import litellm

        if litellm.supports_vision(model=model_name):
            return True
    except Exception:
        # Ignore import errors or runtime failures and fall back to manual list.
        pass

    name_lower = model_name.lower()
    return any(name_lower.startswith(prefix.lower()) for prefix in _VISION_MODELS)


def add_vision_model(model_name: str) -> None:
    """
    Allow callers to register additional model families at runtime.
    """
    _VISION_MODELS.add(model_name)


__all__ = [
    "add_vision_model",
    "supports_vision",
]
