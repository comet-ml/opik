"""OpenAI Text-to-Speech (TTS) usage tracking model."""

from typing import Any, Dict, Optional
import pydantic

from . import base_original_provider_usage


class OpenAITTSUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """
    Usage model for OpenAI Text-to-Speech API.
    
    TTS models are priced per character, not per token like chat completions.
    This class tracks character count for cost calculation.
    
    Pricing (as of 2025):
    - tts-1: $0.015 per 1,000 characters
    - tts-1-hd: $0.030 per 1,000 characters
    """

    characters: int
    """Total number of characters in the input text."""

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "OpenAITTSUsage":
        """
        Create OpenAITTSUsage from the usage dictionary.
        
        For TTS, we track the character count of the input text.
        The OpenAI API doesn't return usage metadata for TTS calls,
        so we calculate it from the input text.
        
        Args:
            usage: Dictionary containing 'characters' key with character count
            
        Returns:
            OpenAITTSUsage instance
        """
        return cls(
            characters=usage.get("characters", 0),
        )

    def to_backend_compatible_flat_dict(
        self, parent_key_prefix: Optional[str] = None
    ) -> Dict[str, int]:
        """
        Convert to backend-compatible flat dictionary.
        
        Args:
            parent_key_prefix: Prefix for keys (e.g., "original_usage")
            
        Returns:
            Flattened dictionary with integer values
        """
        result = {
            "characters": self.characters,
        }

        if parent_key_prefix:
            result = {f"{parent_key_prefix}.{k}": v for k, v in result.items()}

        return result
