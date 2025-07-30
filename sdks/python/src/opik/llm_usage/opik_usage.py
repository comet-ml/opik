import logging
import pydantic
from typing import Union, Dict, Any, Optional
from . import (
    openai_chat_completions_usage,
    google_usage,
    anthropic_usage,
    unknown_usage,
    bedrock_usage,
    openai_responses_usage,
)
import opik.dict_utils as dict_utils

ProviderUsage = Union[
    openai_chat_completions_usage.OpenAICompletionsUsage,
    google_usage.GoogleGeminiUsage,
    anthropic_usage.AnthropicUsage,
    bedrock_usage.BedrockUsage,
    openai_responses_usage.OpenAIResponsesUsage,
    unknown_usage.UnknownUsage,
]

LOGGER = logging.getLogger(__name__)


class OpikUsage(pydantic.BaseModel):
    """
        A class used to convert different formats of token usage dictionaries
    into format supported by Opik ecosystem.

        `from_PROVIDER_usage_dict methods` methods are used to parse original provider's token
    usage dicts and calculate openai-formatted extra key-value pairs (that can later be used on the FE and BE sides).
    """

    completion_tokens: Optional[int] = None
    prompt_tokens: Optional[int] = None
    total_tokens: Optional[int] = None

    provider_usage: ProviderUsage

    def to_backend_compatible_full_usage_dict(self) -> Dict[str, int]:
        """
        Returns usage dictionary in backend compatible format:
            * flattened, original usage keys have `original_usage.` prefix
            * only integer values
            * if available, adds openai-formatted keys to the result dict
                so that they can be used on the BE and FE sides.
        """
        short_openai_like_usage: Dict[str, int] = dict_utils.keep_only_values_of_type(
            {
                "completion_tokens": self.completion_tokens,
                "prompt_tokens": self.prompt_tokens,
                "total_tokens": self.total_tokens,
            },
            value_type=int,
        )

        provider_usage: Dict[str, int] = (
            self.provider_usage.to_backend_compatible_flat_dict(
                parent_key_prefix="original_usage"
            )
        )

        return {**short_openai_like_usage, **provider_usage}

    @classmethod
    def from_unknown_usage_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = unknown_usage.UnknownUsage.from_original_usage_dict(usage)

        return cls(provider_usage=provider_usage)

    @classmethod
    def from_openai_completions_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = openai_chat_completions_usage.OpenAICompletionsUsage.from_original_usage_dict(
            usage
        )

        return cls(
            completion_tokens=provider_usage.completion_tokens,
            prompt_tokens=provider_usage.prompt_tokens,
            total_tokens=provider_usage.total_tokens,
            provider_usage=provider_usage,
        )

    @classmethod
    def from_groq_completions_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        return cls.from_openai_completions_dict(usage)

    @classmethod
    def from_google_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = google_usage.GoogleGeminiUsage.from_original_usage_dict(usage)

        # The completions token should include both the candidates token_count and the thought tokens. Usage differs depending on the models and between VertexAI and Google AI
        # See https://github.com/BerriAI/litellm/pull/10141#discussion_r2052272035
        # Do something similar as: https://github.com/BerriAI/litellm/blob/4854482af4a2a56060bbfeb4345bce4f1bb7ec41/litellm/llms/vertex_ai/gemini/vertex_and_google_ai_studio_gemini.py#L980-L995

        if (
            provider_usage.total_token_count
            == provider_usage.prompt_token_count + provider_usage.candidates_token_count
        ):
            completion_tokens = provider_usage.candidates_token_count
        elif provider_usage.thoughts_token_count is not None:
            completion_tokens = (
                provider_usage.candidates_token_count
                + provider_usage.thoughts_token_count
            )
        else:
            LOGGER.debug(
                "Something is wrong in Google usage, completion_tokens might be invalid"
            )
            completion_tokens = provider_usage.candidates_token_count

        return cls(
            completion_tokens=completion_tokens,
            prompt_tokens=provider_usage.prompt_token_count,
            total_tokens=provider_usage.total_token_count,
            provider_usage=provider_usage,
        )

    @classmethod
    def from_anthropic_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = anthropic_usage.AnthropicUsage.from_original_usage_dict(usage)

        prompt_tokens = provider_usage.input_tokens + (
            provider_usage.cache_read_input_tokens
            if provider_usage.cache_read_input_tokens is not None
            else 0
        )
        completion_tokens = provider_usage.output_tokens + (
            provider_usage.cache_creation_input_tokens
            if provider_usage.cache_creation_input_tokens is not None
            else 0
        )
        total_tokens = prompt_tokens + completion_tokens

        return cls(
            completion_tokens=completion_tokens,
            prompt_tokens=prompt_tokens,
            total_tokens=total_tokens,
            provider_usage=provider_usage,
        )

    @classmethod
    def from_bedrock_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = bedrock_usage.BedrockUsage.from_original_usage_dict(usage)

        prompt_tokens = provider_usage.inputTokens
        completion_tokens = provider_usage.outputTokens

        total_tokens = prompt_tokens + completion_tokens

        return cls(
            completion_tokens=completion_tokens,
            prompt_tokens=prompt_tokens,
            total_tokens=total_tokens,
            provider_usage=provider_usage,
        )

    @classmethod
    def from_openai_responses_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = (
            openai_responses_usage.OpenAIResponsesUsage.from_original_usage_dict(usage)
        )

        return cls(
            completion_tokens=provider_usage.output_tokens,
            prompt_tokens=provider_usage.input_tokens,
            total_tokens=provider_usage.total_tokens,
            provider_usage=provider_usage,
        )
