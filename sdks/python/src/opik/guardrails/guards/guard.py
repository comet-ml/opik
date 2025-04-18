import abc
from typing import List, Dict, Any


class Guard(abc.ABC):
    # Is this guard running locally or remotely on the Guardrail API
    remote = False

    @abc.abstractmethod
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        Get the validation configuration for this guard.

        Returns:
            List of validation configurations to be sent to the API
        """
        pass
