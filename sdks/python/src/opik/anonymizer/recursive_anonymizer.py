import abc
from typing import Any, Optional

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
        return self._recursive_anonymize(data, **kwargs)

    @abc.abstractmethod
    def anonymize_text(self, data: str, **kwargs: Any) -> str:
        pass

    def _recursive_anonymize(
        self,
        data: anonymizer.AnonymizerDataType,
        depth: int = 0,
        field_name: Optional[str] = None,
        **kwargs: Any,
    ) -> anonymizer.AnonymizerDataType:
        if depth >= self.max_depth:
            return data

        if field_name is None:
            field_name = ""

        if isinstance(data, str):
            return self.anonymize_text(data, field_name=field_name, **kwargs)
        elif isinstance(data, dict):
            return {
                key: self._recursive_anonymize(
                    value, depth + 1, field_name=f"{field_name}.{key}", **kwargs
                )
                for key, value in data.items()
            }
        elif isinstance(data, list):
            return [
                self._recursive_anonymize(
                    item, depth + 1, field_name=f"{field_name}.{i}", **kwargs
                )
                for i, item in enumerate(data)
            ]
        else:
            return data
