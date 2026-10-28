import abc
import enum
from typing import List, Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from .. import schemas
    from opik.api_objects import opik_client


class RuleLocation(str, enum.Enum):
    """Where a rule is checked."""

    # By the Opik backend, on the Opik inference service.
    OPIK = "opik"
    # In the SDK itself.
    LOCAL = "local"
    # By a self-hosted Opik guardrails service.
    GUARDRAILS_SERVICE = "guardrails_service"


class Rule(abc.ABC):
    location: RuleLocation = RuleLocation.OPIK

    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for this rule, sent to wherever it is checked.
        Local rules do not send anything and return an empty list.

        Returns:
            List of validation configurations to be sent to the API
        """
        return []

    def validate_local(
        self, text: str, client: "opik_client.Opik"
    ) -> List["schemas.ValidationResult"]:
        """
        Run this rule locally in the SDK. Only called for rules located in the SDK.

        Args:
            text: Text to validate
            client: Opik client, used to reach Opik backend endpoints

        Returns:
            List of validation results produced by this rule
        """
        raise NotImplementedError
