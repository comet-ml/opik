from typing import List, Dict, Any
from . import guard
from .. import schemas
import functools


class CustomGuardrail(guard.Guard):
    """
    Guard backed by a custom binary classifier that you trained on your own labeled
    examples.

    The model is served by the guardrails backend, which loads it by name from its
    local adapters directory, so the guardrails server (with the trained model
    available to it) must be running.
    """

    def __init__(
        self,
        model_name: str,
        threshold: float = 0.5,
    ) -> None:
        """
        Initialize a custom guardrail.

        Args:
            model_name: Name of the trained model to run, as returned by training.
            threshold: Score threshold above which the guard fails (default: 0.5).
                Lower it to be stricter, raise it to be more permissive.
        """
        self._model_name = model_name
        self._threshold = threshold

    @functools.lru_cache()
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for the custom guardrail.

        Returns:
            List containing the custom guardrail validation configuration
        """
        return [
            {
                "type": schemas.ValidationType.CUSTOM_CLASSIFIER,
                "config": {
                    "model_name": self._model_name,
                    "threshold": self._threshold,
                },
            }
        ]
