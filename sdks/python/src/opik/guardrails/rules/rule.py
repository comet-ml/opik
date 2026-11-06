import abc
from typing import Any, Dict, List


class Rule(abc.ABC):
    """A check the Opik backend runs on the text."""

    @abc.abstractmethod
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configurations this rule sends to the Opik backend.

        Returns:
            List of validation configurations to be sent to the API
        """
