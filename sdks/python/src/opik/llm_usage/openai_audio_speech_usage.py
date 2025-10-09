from typing import Dict, Any

from opik import dict_utils
import pydantic


class OpenAIAudioSpeechUsage(pydantic.BaseModel):
    """
    A class used to represent the token usage of a call to OpenAI's audio speech API.
    """

    total_tokens: int

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, Any]:
        """
        For example:
        {
            "original_usage.total_tokens": 12,
        }
        """
        original_usage: Dict[
            str, int
        ] = dict_utils.add_prefix_to_keys_of_a_dict(  # type: ignore
            self.model_dump(), parent_key_prefix
        )

        return original_usage

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "OpenAIAudioSpeechUsage":
        return cls(**usage) 