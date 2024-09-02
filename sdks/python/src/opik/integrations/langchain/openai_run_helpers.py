from typing import Dict, Any, Optional, TYPE_CHECKING, cast
from opik.types import UsageDict

import logging

from opik.validation import usage as usage_validator
from opik import logging_messages

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run


LOGGER = logging.getLogger(__name__)


def try_get_token_usage(run_dict: Dict[str, Any]) -> Optional[UsageDict]:
    try:
        token_usage = run_dict["outputs"]["llm_output"]["token_usage"]
        if usage_validator.UsageValidator(token_usage).validate().ok():
            return cast(UsageDict, token_usage)

        return None
    except Exception:
        LOGGER.warning(
            logging_messages.FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_OPENAI_LLM_RUN,
            exc_info=True,
        )
        return None


def is_openai_run(run: "Run") -> bool:
    try:
        if run.serialized is None:
            return False

        source_is_langchain_openai_class: bool = run.serialized["id"] == [
            "langchain",
            "llms",
            "openai",
            "OpenAI",
        ]

        return source_is_langchain_openai_class

    except Exception:
        LOGGER.debug(
            "Failed to check if Run instance is from OpenAI LLM, returning False.",
            exc_info=True,
        )
        return False
