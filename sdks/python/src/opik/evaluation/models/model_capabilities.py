"""
Model capability detection for LLM evaluation.

This module provides functionality to detect whether a model supports specific capabilities
like vision (image input support).
"""

from typing import Optional, Set

# Known vision-capable models (expandable list)
# Format: model prefixes or full model names that support image inputs
VISION_MODELS: Set[str] = {
    # OpenAI
    "gpt-4-vision",
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-4-turbo",
    "chatgpt-4o-latest",
    # Anthropic
    "claude-3",
    "claude-3-opus",
    "claude-3-sonnet",
    "claude-3-haiku",
    "claude-3-5-sonnet",
    "claude-3-5-haiku",
    # Google
    "gemini-1.5-pro",
    "gemini-1.5-flash",
    "gemini-2.0-flash-exp",
    "gemini-pro-vision",
    # Meta
    "llama-3.2-11b-vision",
    "llama-3.2-90b-vision",
    # Mistral
    "pixtral",
    # Others
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
    Check if a model supports vision capabilities (image inputs).

    Uses LiteLLM's built-in vision support check when available, with fallback
    to a curated list of known vision models.

    Args:
        model_name: The name of the model to check

    Returns:
        bool: True if the model supports vision, False otherwise

    Examples:
        >>> supports_vision("gpt-4o")
        True
        >>> supports_vision("gpt-3.5-turbo")
        False
        >>> supports_vision("claude-3-opus-20240229")
        True
        >>> supports_vision(None)
        False
    """
    if not model_name:
        return False

    # Try using LiteLLM's built-in vision support check
    try:
        import litellm
        if litellm.supports_vision(model=model_name):
            return True
        # If LiteLLM returns False, fall through to manual check
    except Exception:
        # Fall back to manual check if LiteLLM check fails
        pass

    # Normalize model name to lowercase for matching
    model_lower = model_name.lower()

    # Check if model name starts with any known vision model prefix
    return any(model_lower.startswith(vision_model.lower()) for vision_model in VISION_MODELS)


def add_vision_model(model_name: str) -> None:
    """
    Add a custom model to the vision models list.

    This allows users to extend support for custom or newly released vision models.

    Args:
        model_name: The model name or prefix to add

    Example:
        >>> add_vision_model("custom-vision-model")
        >>> supports_vision("custom-vision-model-v1")
        True
    """
    VISION_MODELS.add(model_name)
