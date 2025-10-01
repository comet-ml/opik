from typing import Any, TYPE_CHECKING

import logging
import os
import time
import random

import litellm
from litellm import exceptions as litellm_exceptions
from litellm.caching import Cache
from litellm.types.caching import LiteLLMCacheType
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

from .. import _throttle


logger = logging.getLogger(__name__)


# Configure LiteLLM cache with safe fallback
try:
    # Prefer a disk cache in a user-writable location
    cache_dir = os.path.join(os.path.expanduser("~"), ".cache", "litellm")
    os.makedirs(cache_dir, exist_ok=True)
    litellm.cache = Cache(type=LiteLLMCacheType.DISK, cache_dir=cache_dir)
except (PermissionError, OSError, FileNotFoundError):
    # Fall back to in-memory cache to avoid disk timeouts/locks
    litellm.cache = Cache(type=LiteLLMCacheType.MEMORY)

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class LlmSupport:
    if TYPE_CHECKING:
        model: str
        llm_call_counter: int
        project_name: str | None
        disable_litellm_monitoring: bool
        temperature: float
        max_tokens: int
        top_p: float
        frequency_penalty: float
        presence_penalty: float

        def increment_llm_counter(self) -> None: ...

    @_throttle.rate_limited(_rate_limiter)
    def _call_model(
        self,
        messages: list[dict[str, str]],
        is_reasoning: bool = False,
        optimization_id: str | None = None,
    ) -> str:
        """Call the model with the given prompt and return the response string."""
        # For reasoning calls (prompt generation), use higher max_tokens to avoid truncation
        # For evaluation calls (task output), use user-configurable max_tokens
        default_max_tokens = 8000 if is_reasoning else 1000

        # Build base call params
        llm_config_params: dict[str, Any] = {
            "temperature": getattr(self, "temperature", 0.3),
            "max_tokens": getattr(self, "max_tokens", default_max_tokens),
            "top_p": getattr(self, "top_p", 1.0),
            "frequency_penalty": getattr(self, "frequency_penalty", 0.0),
            "presence_penalty": getattr(self, "presence_penalty", 0.0),
        }

        # Add Opik metadata unless disabled
        try:
            disable_monitoring_env = os.getenv(
                "OPIK_OPTIMIZER_DISABLE_LITELLM_MONITORING", "0"
            )
            disable_monitoring = getattr(
                self, "disable_litellm_monitoring", False
            ) or disable_monitoring_env.lower() in ("1", "true", "yes")

            if not disable_monitoring:
                metadata_for_opik: dict[str, Any] = {}
                pn = getattr(self, "project_name", None)
                if pn:
                    metadata_for_opik["project_name"] = pn
                    metadata_for_opik["opik"] = {"project_name": pn}
                if optimization_id and "opik" in metadata_for_opik:
                    metadata_for_opik["opik"]["optimization_id"] = optimization_id
                metadata_for_opik["optimizer_name"] = self.__class__.__name__
                metadata_for_opik["opik_call_type"] = (
                    "reasoning" if is_reasoning else "evaluation_llm_task_direct"
                )
                if metadata_for_opik:
                    llm_config_params["metadata"] = metadata_for_opik

                # Try to add Opik monitoring callbacks; fall back silently on failure
                llm_config_params = (
                    opik_litellm_monitor.try_add_opik_monitoring_to_params(  # type: ignore
                        llm_config_params.copy()
                    )
                )
        except Exception as e:
            logger.debug(f"Skipping Opik-LiteLLM monitoring setup: {e}")

        # Retry policy for transient errors
        max_retries = int(os.getenv("OPIK_OPTIMIZER_LITELLM_MAX_RETRIES", "3"))
        base_sleep = float(os.getenv("OPIK_OPTIMIZER_LITELLM_BACKOFF", "0.5"))

        for attempt in range(max_retries + 1):
            try:
                logger.debug(
                    f"Calling model '{self.model}' with messages: {messages}, params: {llm_config_params} (attempt {attempt + 1})"
                )
                response = litellm.completion(
                    model=self.model, messages=messages, **llm_config_params
                )
                self.increment_llm_counter()
                return response.choices[0].message.content
            except (
                litellm_exceptions.RateLimitError,
                litellm_exceptions.APIConnectionError,
                litellm_exceptions.InternalServerError,
            ) as e:
                if attempt < max_retries:
                    sleep_s = min(10.0, base_sleep * (2**attempt)) + random.uniform(
                        0, 0.25
                    )
                    logger.warning(
                        f"LiteLLM transient error ({type(e).__name__}): {e}. Retrying in {sleep_s:.2f}s..."
                    )
                    time.sleep(sleep_s)
                    continue
                logger.error(f"LiteLLM error (final attempt): {e}")
                raise
            except litellm_exceptions.ContextWindowExceededError as e:
                logger.error(f"LiteLLM Context Window Exceeded Error: {e}")
                raise
            except Exception as e:
                logger.error(
                    f"Error calling model '{self.model}': {type(e).__name__} - {e}"
                )
                raise
        # Should never reach here
        raise RuntimeError("LLM call did not return a response and did not raise")
