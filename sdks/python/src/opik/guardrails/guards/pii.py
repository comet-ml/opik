from typing import List, Dict, Any, Optional
from . import guard
from .. import schemas
import functools


class PII(guard.Guard):
    """
    Guard that validates text for personally identifiable information (PII).
    """

    def __init__(
        self,
        blocked_entities: Optional[List[str]] = None,
        language: str = "en",
        threshold: float = 0.5,
    ) -> None:
        """
        Initialize a PII guard.

        Args:
            blocked_entities: List of PII entity types to block.
                Default entities include: "IP_ADDRESS", "PHONE_NUMBER", "PERSON", "MEDICAL_LICENSE", "URL", "EMAIL_ADDRESS", "IBAN_CODE".
            Supported entities can be found here: https://microsoft.github.io/presidio/supported_entities/
        """
        self._blocked_entities = blocked_entities
        self._language = language
        self._threshold = threshold

    @functools.lru_cache()
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for PII detection.

        Returns:
            List containing the PII validation configuration
        """
        return [
            {
                "type": schemas.ValidationType.PII,
                "config": {
                    "entities": self._blocked_entities,
                    "language": self._language,
                    "threshold": self._threshold,
                },
            }
        ]
