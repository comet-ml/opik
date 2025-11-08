"""
Capability registry for evaluation models.

The registry is designed to grow beyond vision support (e.g. audio in the future).
"""

from __future__ import annotations

from typing import Callable, Dict, Iterable, Optional, Set

CapabilityDetector = Callable[[str], bool]


VISION_MODEL_PREFIXES: Set[str] = {
    # OpenAI
    "gpt-4-vision",
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-4-turbo",
    "chatgpt-4o-latest",
    "gpt-5-mini",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4.1-preview",
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
VISION_MODEL_PREFIXES = {prefix.lower() for prefix in VISION_MODEL_PREFIXES}
VISION_MODEL_SUFFIXES: Set[str] = {"-vision", "-vl"}


def _strip_provider_prefix(model_name: str) -> str:
    if "/" not in model_name:
        return model_name
    _, suffix = model_name.split("/", 1)
    return suffix


def _litellm_supports_vision(model_name: str) -> bool:
    try:
        import litellm  # type: ignore

        return litellm.supports_vision(model=model_name)
    except Exception:
        return False


def vision_capability_detector(model_name: str) -> bool:
    stripped = _strip_provider_prefix(model_name)
    candidates = {model_name, stripped}
    for candidate in candidates:
        if _litellm_supports_vision(candidate):
            return True
        normalized = candidate.lower()
        if any(normalized.startswith(prefix) for prefix in VISION_MODEL_PREFIXES):
            return True
        if any(normalized.endswith(suffix) for suffix in VISION_MODEL_SUFFIXES):
            return True
    return False


def video_capability_detector(model_name: str) -> bool:
    """
    Heuristically determine whether a model accepts video inputs.

    Providers rarely expose structured metadata for video support, so we fall back
    to naming conventions (e.g. models whose names contain ``video`` or ``qwen``
    + ``vl``). When those heuristics fail we delegate to the vision detector since
    current SDK integrations treat video as an extension of multimodal/vision APIs.
    """
    stripped = _strip_provider_prefix(model_name)
    candidates = {model_name, stripped}
    for candidate in candidates:
        normalized = candidate.lower()
        if "video" in normalized:
            return True
        if "qwen" in normalized and "vl" in normalized:
            return True
    # TODO(opik): litellm/model metadata still treats video + image inputs the same.
    # Fall back to the vision heuristic so we can keep this dedicated capability
    # and tighten detection once providers expose richer metadata.
    return vision_capability_detector(model_name)


class ModelCapabilitiesRegistry:
    """
    Central registry for model capability detection.
    """

    def __init__(self) -> None:
        self._capability_detectors: Dict[str, CapabilityDetector] = {}

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

    def supports_video(self, model_name: Optional[str]) -> bool:
        """
        Convenience wrapper for video-capable detection.
        """
        return self.supports("video", model_name)

    def add_vision_model(self, model_name: str) -> None:
        # Extend the module-level registry used by vision_capability_detector
        VISION_MODEL_PREFIXES.add(self._strip_provider_prefix(model_name).lower())

    def add_vision_models(self, model_names: Iterable[str]) -> None:
        for model_name in model_names:
            self.add_vision_model(model_name)

    def _supports_vision(self, model_name: str) -> bool:
        return vision_capability_detector(model_name)

    @staticmethod
    def _strip_provider_prefix(model_name: str) -> str:
        return _strip_provider_prefix(model_name)

    @staticmethod
    def _litellm_supports_vision(model_name: str) -> bool:
        return _litellm_supports_vision(model_name)


MODEL_CAPABILITIES_REGISTRY = ModelCapabilitiesRegistry()
MODEL_CAPABILITIES_REGISTRY.register_capability_detector(
    "vision", vision_capability_detector
)
MODEL_CAPABILITIES_REGISTRY.register_capability_detector(
    "video", video_capability_detector
)

# Backwards compatibility shim for previous API which exposed a class with classmethods.
ModelCapabilities = MODEL_CAPABILITIES_REGISTRY


__all__ = [
    "ModelCapabilitiesRegistry",
    "MODEL_CAPABILITIES_REGISTRY",
    "ModelCapabilities",
    "vision_capability_detector",
    "video_capability_detector",
]
