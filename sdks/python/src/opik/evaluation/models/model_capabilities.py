"""
Capability registry for evaluation models.

The registry is designed to grow beyond vision support (e.g. audio in the future).
"""

from __future__ import annotations

from typing import Callable, Dict, Iterable, Optional, Set

CapabilityDetector = Callable[[str], bool]


class ModelCapabilitiesRegistry:
    """
    Central registry for model capability detection.
    """

    def __init__(self) -> None:
        self._capability_detectors: Dict[str, CapabilityDetector] = {}
        self._vision_model_prefixes: Set[str] = {
            # OpenAI
            "gpt-4-vision",
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "chatgpt-4o-latest",
            "gpt-5-mini",
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
        self._vision_model_prefixes = {
            prefix.lower() for prefix in self._vision_model_prefixes
        }

    def register_capability_detector(
        self, capability: str, detector: CapabilityDetector
    ) -> None:
        """
        Register a detector callable for a capability name.
        """
        self._capability_detectors[capability] = detector

    def supports(self, capability: str, model_name: Optional[str]) -> bool:
        """
        Return True when the supplied model name supports the requested capability.
        """
        if not model_name:
            return False

        detector = self._capability_detectors.get(capability)
        if detector is None:
            return False

        try:
            return detector(model_name)
        except Exception:
            return False

    def supports_vision(self, model_name: Optional[str]) -> bool:
        """
        Convenience wrapper for vision-capable detection.
        """
        return self.supports("vision", model_name)

    def add_vision_model(self, model_name: str) -> None:
        self._vision_model_prefixes.add(self._strip_provider_prefix(model_name).lower())

    def add_vision_models(self, model_names: Iterable[str]) -> None:
        for model_name in model_names:
            self.add_vision_model(model_name)

    def _supports_vision(self, model_name: str) -> bool:
        stripped = self._strip_provider_prefix(model_name)
        candidates = {model_name, stripped}

        for candidate in candidates:
            if self._litellm_supports_vision(candidate):
                return True
            normalized = candidate.lower()
            if any(
                normalized.startswith(prefix) for prefix in self._vision_model_prefixes
            ):
                return True

        return False

    @staticmethod
    def _strip_provider_prefix(model_name: str) -> str:
        if "/" not in model_name:
            return model_name
        _, suffix = model_name.split("/", 1)
        return suffix

    @staticmethod
    def _litellm_supports_vision(model_name: str) -> bool:
        try:
            import litellm  # type: ignore

            return litellm.supports_vision(model=model_name)
        except Exception:
            return False


MODEL_CAPABILITIES_REGISTRY = ModelCapabilitiesRegistry()
MODEL_CAPABILITIES_REGISTRY.register_capability_detector(
    "vision", MODEL_CAPABILITIES_REGISTRY._supports_vision
)

# Backwards compatibility shim for previous API which exposed a class with classmethods.
ModelCapabilities = MODEL_CAPABILITIES_REGISTRY


__all__ = [
    "ModelCapabilitiesRegistry",
    "MODEL_CAPABILITIES_REGISTRY",
    "ModelCapabilities",
]
