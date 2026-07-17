from typing import List, Dict, Any
from . import guard
from .. import schemas
import functools


class PromptInjection(guard.Guard):
    """
    Guard that detects prompt injection and jailbreak attempts.

    Runs a fine-tuned classifier on the guardrails backend, which requires the
    guardrails server (with a Hugging Face token configured) to be running.
    """

    def __init__(
        self,
        threshold: float = 0.5,
    ) -> None:
        """
        Initialize a prompt injection guard.

        Args:
            threshold: Injection-probability threshold above which the guard fails
                (default: 0.5). Lower it to be stricter, raise it to be more permissive.
        """
        self._threshold = threshold

    @functools.lru_cache()
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for prompt injection detection.

        Returns:
            List containing the prompt injection validation configuration
        """
        return [
            {
                "type": schemas.ValidationType.PROMPT_INJECTION,
                "config": {
                    "threshold": self._threshold,
                },
            }
        ]
