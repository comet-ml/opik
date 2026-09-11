import abc
from typing import List, Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from .. import schemas
    from opik.api_objects import opik_client


class Guard(abc.ABC):
    # Whether this guard executes locally in the SDK (True) or remotely on the
    # guardrails backend (False).
    local: bool = False

    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for this guard, to be sent to the
        guardrails backend. Local guards do not run on the backend and return
        an empty list.

        Returns:
            List of validation configurations to be sent to the API
        """
        return []

    def validate_local(
        self, text: str, client: "opik_client.Opik"
    ) -> List["schemas.ValidationResult"]:
        """
        Run this guard locally in the SDK. Only called for guards with ``local = True``.

        Args:
            text: Text to validate
            client: Opik client, used to reach Opik backend endpoints

        Returns:
            List of validation results produced by this guard
        """
        raise NotImplementedError
