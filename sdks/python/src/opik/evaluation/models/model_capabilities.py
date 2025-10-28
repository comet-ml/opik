"""
Capability registry for evaluation models.

The registry is designed to grow beyond vision support (e.g. audio in the future).
"""

from __future__ import annotations

from typing import Callable, Dict, Optional, Set

CapabilityDetector = Callable[[str], bool]


class ModelCapabilities:
    """
    Central registry for model capability detection.

    Capabilities are keyed by name (``"vision"``, ``"audio"``, etc.) and resolved
    through detector callables. The class ships with a vision detector but can be
    extended by consumers without modifying core SDK code.
    """

    _capability_detectors: Dict[str, CapabilityDetector] = {}

    # Known families or exact identifiers for vision-capable models. Consumers can
    # extend this list at runtime via :meth:`add_vision_model`.
    _vision_models: Set[str] = {
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

    @classmethod
    def register_capability_detector(
        cls, capability: str, detector: CapabilityDetector
    ) -> None:
        """
        Register a detector callable for a capability name.
        """
        cls._capability_detectors[capability] = detector

    @classmethod
    def supports(cls, capability: str, model_name: Optional[str]) -> bool:
        """
        Return True when the supplied model name supports the requested capability.
        """
        if not model_name:
            return False

        detector = cls._capability_detectors.get(capability)
        if detector is None:
            return False

        try:
            return detector(model_name)
        except Exception:
            return False

    @classmethod
    def supports_vision(cls, model_name: Optional[str]) -> bool:
        """
        Convenience wrapper for vision-capable detection.
        """
        return cls.supports("vision", model_name)

    @classmethod
    def add_vision_model(cls, model_name: str) -> None:
        cls._vision_models.add(model_name)

    @classmethod
    def _supports_vision(cls, model_name: str) -> bool:
        # Try LiteLLM's detector when available.
        try:
            import litellm

            if litellm.supports_vision(model=model_name):
                return True
        except Exception:
            pass

        name_lower = model_name.lower()
        return any(
            name_lower.startswith(prefix.lower()) for prefix in cls._vision_models
        )


# Register built-in detectors.
ModelCapabilities.register_capability_detector(
    "vision", ModelCapabilities._supports_vision
)


__all__ = ["ModelCapabilities"]
