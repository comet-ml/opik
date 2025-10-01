from opik import llm_usage
from typing import Dict, Any, Optional
import logging
import opik._logging


LOGGER = logging.getLogger(__name__)


def extract_subprovider_from_model_id(model_id: str) -> str:
    """
    Extracts the provider name from a Bedrock modelId.

    Examples:
        ai21.j2-mid-v1                -> ai21
        amazon.nova-lite-v1:0         -> amazon
        anthropic.claude-v2:1         -> anthropic
        us.meta.llama3-1-70b-instruct -> meta
    """
    parts = model_id.split(".")

    if parts[0] in {"us", "eu", "apac"}:
        return parts[1]

    return parts[0]


def try_extract_usage_from_bedrock_response(
    subprovider: str, response: Dict[str, Any]
) -> Optional[llm_usage.OpikUsage]:
    """
    Since Bedrock's invoke_model response format is not standardized, we need to try different ways to extract the usage.

    This usage may also be not in Bedrock's format, but in the format of the original subprovider.
    """
    try:
        if subprovider == "anthropic":
            usage_dict = response["body"]["usage"]
            bedrock_formatted_usage = _anthropic_to_bedrock_usage(usage_dict)
            opik_usage = llm_usage.OpikUsage.from_bedrock_dict(bedrock_formatted_usage)

            return opik_usage

        # This is the default case, but it's not guaranteed to find the usage here for all possible subproviders
        presumably_usage_dict = response["body"]["usage"]

        # If it's already in Bedrock's format, we are good (tested with amazon.nova-pro-v1:0, it has bedrock usage format)
        # If it's not, but it's in some other format that Opik supports, we will at least extract
        # completion and prompt tokens count so that backend could calculate cost based on them.
        opik_usage = llm_usage.build_opik_usage_from_unknown_provider(
            presumably_usage_dict
        )
        return opik_usage

    except Exception:
        opik._logging.log_once_at_level(
            logging.WARNING,
            f"Failed to extract usage from Bedrock's invoke_model response: {response}. It may be because this model response format is currently not supported: please create an issue at https://github.com/opik-ai/opik/issues and we will add support for it.",
            LOGGER,
        )
        return None


def _anthropic_to_bedrock_usage(anthropic_usage: Dict[str, Any]) -> Dict[str, Any]:
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
