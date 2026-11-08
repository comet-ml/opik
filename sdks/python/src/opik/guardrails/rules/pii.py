from typing import List, Dict, Any

from . import rule
from .. import schemas


class PII(rule.Rule):
    """
    Rule that detects personally identifiable information (PII) in the text.

    Checked by the Opik backend.
    """

    def __init__(self, entities: List[str]) -> None:
        """
        Initialize a PII rule.

        Args:
            entities: Classes of personal data to detect, any of "CREDIT_CARD", "PHONE_NUMBER",
                "EMAIL_ADDRESS", "IBAN_CODE", "IP_ADDRESS", "NRP" (vehicle registration plate),
                "LOCATION" (postal address), "PERSON", "CRYPTO", "MEDICAL_LICENSE" and "URL".
        """
        self._entities = entities

    def get_validation_configs(self) -> List[Dict[str, Any]]:
        return [
            {
                "type": schemas.ValidationType.PII,
                "config": {"entities": self._entities},
            }
        ]
