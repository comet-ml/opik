"""Shared usage conversion utilities for Bedrock models."""

from typing import Any, Dict


def anthropic_to_bedrock_usage(anthropic_usage: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert Anthropic-style usage schema into Bedrock-style usage schema.

    Anthropic usage keys (snake_case):
      - input_tokens
      - output_tokens
      - cache_creation_input_tokens
      - cache_read_input_tokens

    Bedrock usage keys (camelCase):
      - inputTokens
      - outputTokens
      - cacheWriteInputTokens
      - cacheReadInputTokens
      - totalTokens
    """
    input_tokens = anthropic_usage.get("input_tokens", 0)
    output_tokens = anthropic_usage.get("output_tokens", 0)
    cache_write = anthropic_usage.get("cache_creation_input_tokens", 0)
    cache_read = anthropic_usage.get("cache_read_input_tokens", 0)

    return {
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "cacheWriteInputTokens": cache_write,
        "cacheReadInputTokens": cache_read,
        "totalTokens": input_tokens + output_tokens,
    }


def llama_to_bedrock_usage(llama_usage: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert Llama-style usage schema into Bedrock-style usage schema.

    Llama usage keys:
      - prompt_token_count
      - generation_token_count
    """
    input_tokens = llama_usage.get("prompt_token_count", 0)
    output_tokens = llama_usage.get("generation_token_count", 0)

    return {
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "totalTokens": input_tokens + output_tokens,
    }


def openai_to_bedrock_usage(openai_usage: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert OpenAI-style usage schema into Bedrock-style usage schema.
    Used by Mistral/Pixtral models.

    OpenAI usage keys:
      - prompt_tokens
      - completion_tokens
      - total_tokens
    """
    input_tokens = openai_usage.get("prompt_tokens", 0)
    output_tokens = openai_usage.get("completion_tokens", 0)

    return {
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "totalTokens": input_tokens + output_tokens,
    }


def nova_to_bedrock_usage(nova_usage: Dict[str, Any]) -> Dict[str, Any]:
    """
    Convert Nova-style usage (already in Bedrock format) - pass through.
    Nova already uses Bedrock format (inputTokens, outputTokens).
    """
    input_tokens = nova_usage.get("inputTokens", 0)
    output_tokens = nova_usage.get("outputTokens", 0)

    return {
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "totalTokens": input_tokens + output_tokens,
    }
