from opik import llm_usage
from typing import Dict, Any, Optional
import logging
import opik._logging

from . import usage_converters

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
        LOGGER.debug("Extracting usage for subprovider: %s", subprovider)

        if subprovider == "anthropic":
            usage_dict = response["body"]["usage"]
            bedrock_formatted_usage = usage_converters.anthropic_to_bedrock_usage(
                usage_dict
            )
            opik_usage = llm_usage.OpikUsage.from_bedrock_dict(bedrock_formatted_usage)
            LOGGER.debug("Anthropic usage extracted: %s", bedrock_formatted_usage)
            return opik_usage

        elif subprovider == "meta":
            # Llama models have usage fields directly in body (not in body.usage)
            body = response.get("body", {})
            if "prompt_token_count" in body or "generation_token_count" in body:
                bedrock_formatted_usage = usage_converters.llama_to_bedrock_usage(body)
                opik_usage = llm_usage.OpikUsage.from_bedrock_dict(
                    bedrock_formatted_usage
                )
                LOGGER.debug("Llama usage extracted: %s", bedrock_formatted_usage)
                return opik_usage

        elif subprovider == "mistral":
            # Mistral/Pixtral models use OpenAI-like usage format
            usage_dict = response["body"].get("usage", {})
            if usage_dict:
                bedrock_formatted_usage = usage_converters.openai_to_bedrock_usage(
                    usage_dict
                )
                opik_usage = llm_usage.OpikUsage.from_bedrock_dict(
                    bedrock_formatted_usage
                )
                LOGGER.debug("Mistral usage extracted: %s", bedrock_formatted_usage)
                return opik_usage

        elif subprovider == "amazon":
            # Nova models already use Bedrock format
            usage_dict = response["body"].get("usage", {})
            if usage_dict:
                bedrock_formatted_usage = usage_converters.nova_to_bedrock_usage(
                    usage_dict
                )
                opik_usage = llm_usage.OpikUsage.from_bedrock_dict(
                    bedrock_formatted_usage
                )
                LOGGER.debug("Nova usage extracted: %s", bedrock_formatted_usage)
                return opik_usage

        # Fallback: This is the default case, but it's not guaranteed to find the usage here for all possible subproviders
        presumably_usage_dict = response["body"].get("usage", {})
        if presumably_usage_dict:
            # If it's already in Bedrock's format, we are good (tested with amazon.nova-pro-v1:0, it has bedrock usage format)
            # If it's not, but it's in some other format that Opik supports, we will at least extract
            # completion and prompt tokens count so that backend could calculate cost based on them.
            opik_usage = llm_usage.build_opik_usage_from_unknown_provider(
                presumably_usage_dict
            )
            LOGGER.debug("Fallback usage extracted: %s", presumably_usage_dict)
            return opik_usage

        LOGGER.debug("No usage found in response body")
        return None

    except Exception as e:
        LOGGER.debug("Exception during usage extraction: %s", e)
        opik._logging.log_once_at_level(
            logging.WARNING,
            f"Failed to extract usage from Bedrock's invoke_model response: {response}. It may be because this model response format is currently not supported: please create an issue at https://github.com/opik-ai/opik/issues and we will add support for it.",
            LOGGER,
        )
        return None
