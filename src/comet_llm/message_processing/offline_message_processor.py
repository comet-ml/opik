import logging
from typing import Optional

from .. import llm_result
from . import messages
from .offline_senders import chain, prompt

LOGGER = logging.getLogger(__name__)


class OfflineMessageProcessor:
    def __init__(
        self, offline_directory: str, batch_duration_seconds: int
    ) -> Optional[llm_result.LLMResult]:
        self._offline_directory = offline_directory
        self._batch_duration_seconds = batch_duration_seconds

    def process(message: messages.BaseMessage) -> None:
        if isinstance(message, messages.PromptMessage):
            try:
                return prompt.send_prompt(message)
            except Exception:
                LOGGER.error("Failed to log prompt", exc_info=True)
        elif isinstance(message, messages.ChainMessage):
            try:
                return chain.send_chain(message)
            except Exception:
                LOGGER.error("Failed to log chain", exc_info=True)

        LOGGER.debug(f"Unsupported message type {message}")
        return None
