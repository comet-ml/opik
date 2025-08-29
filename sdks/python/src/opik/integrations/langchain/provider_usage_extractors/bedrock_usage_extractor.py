import logging
from typing import TYPE_CHECKING, Any, Dict, Optional, List

import opik
from opik import llm_usage, _logging as opik_logging, logging_messages
from . import provider_usage_extractor_protocol
from . import langchain_run_helpers
from .langchain_run_helpers import langchain_usage

if TYPE_CHECKING:
    pass

LOGGER = logging.getLogger(__name__)


class BedrockUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
    PROVIDER = opik.LLMProvider.BEDROCK

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            class_id: List[str] = run_dict.get("serialized", {}).get("id", [])
            if len(class_id) == 0:
                return False

            class_name = class_id[-1]
            is_bedrock = "ChatBedrock" in class_name

            return is_bedrock

        except Exception:
            LOGGER.debug(
                "Failed to check if Run instance is from Bedrock LLM, returning False.",
                exc_info=True,
            )
            return False

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        usage_dict = _try_get_token_usage(run_dict)
        model = _try_get_model_name(run_dict)

        return llm_usage.LLMUsageInfo(
            provider=self.PROVIDER, model=model, usage=usage_dict
        )


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        if token_usage := langchain_run_helpers.try_to_get_usage_by_search(
            run_dict, candidate_keys=None
        ):
            if isinstance(token_usage, langchain_usage.LangChainUsage):
                bedrock_usage_dict = token_usage.map_to_bedrock_usage()
                return llm_usage.OpikUsage.from_bedrock_dict(bedrock_usage_dict)

        opik_logging.log_once_at_level(
            logging.WARNING,
            "Failed to extract token usage from presumably Bedrock LLM langchain run. Run dict: %s",
            LOGGER,
            run_dict,
        )

        opik_logging.log_once_at_level(
            logging_level=logging.WARNING,
            message=logging_messages.WARNING_TOKEN_USAGE_DATA_IS_NOT_AVAILABLE,
            logger=LOGGER,
        )

    except Exception:
        LOGGER.warning(
            "Failed to extract token usage from presumably Bedrock LLM langchain run. Run dict: %s",
            run_dict,
            exc_info=True,
        )

    return None


def _try_get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    MODEL_NAME_KEY = "model_id"

    model = run_dict.get("serialized", {}).get("kwargs", {}).get(MODEL_NAME_KEY, None)

    if model is None:
        LOGGER.error(
            "Failed to extract model name from presumably Bedrock LLM langchain Run object: %s",
            run_dict,
        )

    return model
