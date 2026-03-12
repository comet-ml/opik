"""
Token counting helpers.
"""

import logging
from .. import constants

logger = logging.getLogger(__name__)

try:
    from litellm import token_counter

    LITELLM_TOKEN_COUNTER_AVAILABLE = True
except ImportError:
    LITELLM_TOKEN_COUNTER_AVAILABLE = False
    logger.warning(
        "litellm token_counter not available - token counting will be approximate"
    )


def count_tokens(text: str, model: str = constants.DEFAULT_MODEL) -> int:
    """Count tokens in text using litellm's token_counter or fallback approximation."""
    if LITELLM_TOKEN_COUNTER_AVAILABLE:
        try:
            messages = [{"role": "user", "content": text}]
            return token_counter(model=model, messages=messages)
        except Exception as exc:
            logger.debug("litellm token_counter failed: %s, using fallback", exc)

    return len(text) // 4


def get_max_tokens(model: str) -> int:
    """Return the model's max tokens via litellm."""
    try:
        from litellm import get_max_tokens as _get_max_tokens
    except Exception as exc:
        raise RuntimeError("litellm.get_max_tokens is unavailable") from exc
    return _get_max_tokens(model)
