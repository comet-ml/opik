import abc
from typing import Any

from . import anonymizer


class RecursiveAnonymizer(anonymizer.Anonymizer):
    """Abstract base class for anonymizing sensitive data in various data structures.

    This class provides a framework for recursively anonymizing text content within
    nested data structures such as dictionaries, lists, and strings. Subclasses must
    implement the anonymize_text() method to define the specific anonymization logic.
    """

    def __init__(self, max_depth: int = 10):
        """Initialize the Anonymizer with depth limiting.

        Args:
            max_depth: Maximum recursion depth to prevent infinite loops when
                      processing deeply nested or circular data structures.
                      Defaults to 10.
        """
        self.max_depth = max_depth

    def anonymize(
        self, data: anonymizer.AnonymizerDataType, **kwargs: Any
    ) -> anonymizer.AnonymizerDataType:
        return self._recursive_anonymize(data)

    @abc.abstractmethod
    def anonymize_text(self, data: str) -> str:
        pass

    def _recursive_anonymize(
        self, data: anonymizer.AnonymizerDataType, depth: int = 0
    ) -> anonymizer.AnonymizerDataType:
        if depth >= self.max_depth:
            return data

        if isinstance(data, str):
            return self.anonymize_text(data)
        elif isinstance(data, dict):
            return {
                key: self._recursive_anonymize(value, depth + 1)
                for key, value in data.items()
            }
        elif isinstance(data, list):
            return [self._recursive_anonymize(item, depth + 1) for item in data]
        else:
            return data
