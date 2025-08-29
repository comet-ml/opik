import logging
from typing import Any, Dict, Optional

import opik
from opik import llm_usage, logging_messages, _logging as opik_logging
from . import langchain_run_helpers
from . import provider_usage_extractor_protocol
from .langchain_run_helpers import langchain_usage

LOGGER = logging.getLogger(__name__)


class GoogleGenerativeAIUsageExtractor(
    provider_usage_extractor_protocol.ProviderUsageExtractorProtocol
):
    PROVIDER = opik.LLMProvider.GOOGLE_AI

    def is_provider_run(self, run_dict: Dict[str, Any]) -> bool:
        try:
            if run_dict.get("serialized") is None:
                return False

            if (
                ls_metadata := langchain_run_helpers.try_get_ls_metadata(run_dict)
            ) is not None:
                if "google_genai" == ls_metadata.provider:
                    return True

            if (
                invocation_params := run_dict["extra"].get("invocation_params")
            ) is not None:
                if _is_invocation_param_of_google_gen_ai_type(
                    invocation_params.get("_type").lower()
                ):
                    return True

            return False

        except Exception as ex:
            LOGGER.debug(
                "Failed to check if Run instance is from Google Generative AI, returning False. Reason: %s",
                ex,
                exc_info=True,
            )
            return False

    def get_llm_usage_info(self, run_dict: Dict[str, Any]) -> llm_usage.LLMUsageInfo:
        usage_dict = _try_get_token_usage(run_dict)
        model = _get_model_name(run_dict)

        return llm_usage.LLMUsageInfo(
            provider=self.PROVIDER, model=model, usage=usage_dict
        )


def _try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    try:
        if token_usage := langchain_run_helpers.try_to_get_usage_by_search(
            run_dict, candidate_keys=None
        ):
            if isinstance(token_usage, langchain_usage.LangChainUsage):
                gemini_usage_dict = token_usage.map_to_google_gemini_usage()
                return llm_usage.OpikUsage.from_google_dict(gemini_usage_dict)

        opik_logging.log_once_at_level(
            logging.WARNING,
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GOOGLE_LLM_RUN,
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
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_GOOGLE_LLM_RUN,
            run_dict,
            exc_info=True,
        )

    return None


def _get_model_name(run_dict: Dict[str, Any]) -> Optional[str]:
    """
    Extracts the model name from the run dictionary.
    """
    model = None

    # try metadata first
    if (ls_metadata := langchain_run_helpers.try_get_ls_metadata(run_dict)) is not None:
        model = ls_metadata.model

    elif (invocation_params := run_dict["extra"].get("invocation_params")) is not None:
        model = invocation_params.get("model")

    if model is not None:
        # Gemini **may** add "models/" prefix to some model versions
        model = model.split("/")[-1]

    return model


def _is_invocation_param_of_google_gen_ai_type(provider: str) -> bool:
    return "google-generative-ai" == provider or "google_gemini" == provider
