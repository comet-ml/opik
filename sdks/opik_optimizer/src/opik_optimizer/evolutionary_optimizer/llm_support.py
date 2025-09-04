from typing import Any, Dict, List, Optional

import logging
import os

import litellm
from litellm import exceptions as litellm_exceptions
from litellm.caching import Cache
from litellm.types.caching import LiteLLMCacheType
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

from .. import _throttle


logger = logging.getLogger(__name__)


# Using disk cache for LLM calls (shared across optimizer instances)
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type=LiteLLMCacheType.DISK, disk_cache_dir=disk_cache_dir)

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class LlmSupport:
    @_throttle.rate_limited(_rate_limiter)
    def _call_model(
        self,
        messages: List[Dict[str, str]],
        is_reasoning: bool = False,
        optimization_id: Optional[str] = None,
    ) -> str:
        """Call the model with the given prompt and return the response string."""
        try:
            llm_config_params: Dict[str, Any] = {
                "temperature": getattr(self, "temperature", 0.3),
                "max_tokens": getattr(self, "max_tokens", 1000),
                "top_p": getattr(self, "top_p", 1.0),
                "frequency_penalty": getattr(self, "frequency_penalty", 0.0),
                "presence_penalty": getattr(self, "presence_penalty", 0.0),
            }

            # Metadata for Opik
            metadata_for_opik: Dict[str, Any] = {}
            if getattr(self, "project_name", None):
                metadata_for_opik["project_name"] = self.project_name
                metadata_for_opik["opik"] = {"project_name": self.project_name}
            if optimization_id:
                if "opik" in metadata_for_opik:
                    metadata_for_opik["opik"]["optimization_id"] = optimization_id
            metadata_for_opik["optimizer_name"] = self.__class__.__name__
            metadata_for_opik["opik_call_type"] = (
                "reasoning" if is_reasoning else "evaluation_llm_task_direct"
            )
            if metadata_for_opik:
                llm_config_params["metadata"] = metadata_for_opik

            # Add Opik monitoring params
            final_call_params = opik_litellm_monitor.try_add_opik_monitoring_to_params(
                llm_config_params.copy()
            )

            logger.debug(
                f"Calling model '{self.model}' with messages: {messages}, final params: {final_call_params}"
            )

            response = litellm.completion(
                model=self.model, messages=messages, **final_call_params
            )
            self.llm_call_counter += 1

            logger.debug(f"Response: {response}")
            return response.choices[0].message.content
        except litellm_exceptions.RateLimitError as e:
            logger.error(f"LiteLLM Rate Limit Error: {e}")
            raise
        except litellm_exceptions.APIConnectionError as e:
            logger.error(f"LiteLLM API Connection Error: {e}")
            raise
        except litellm_exceptions.ContextWindowExceededError as e:
            logger.error(f"LiteLLM Context Window Exceeded Error: {e}")
            raise
        except Exception as e:
            logger.error(
                f"Error calling model '{self.model}': {type(e).__name__} - {e}"
            )
            raise