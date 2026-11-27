import abc
from typing import Any, Dict, Optional

from . import anonymizer


class RecursiveAnonymizer(anonymizer.Anonymizer):
    """Abstract base class for anonymizing sensitive data in various data structures.

    This class provides a framework for recursively anonymizing text content within
    nested data structures such as dictionaries, lists, and strings. Subclasses must
    implement the anonymize_text() method to define the specific anonymization logic.
    """

    def __init__(self, max_depth: int = 10, anonymize_dict_keys: bool = False):
        """Initialize the Anonymizer with depth limiting.

        Args:
            max_depth: Maximum recursion depth to prevent infinite loops when
                      processing deeply nested or circular data structures.
                      Defaults to 10.
            anonymize_dict_keys: Also pass string dictionary keys through
                      ``anonymize_text()``, so personal data used as a key
                      (for example a mapping from e-mail to score) is redacted.
                      Off by default because broad rules would also rename
                      ordinary keys (``\\d+`` turns ``"level1"`` into
                      ``"level***"``). Keys that redact to the same text are
                      kept apart with a numeric suffix.
        """
        self.max_depth = max_depth
        self.anonymize_dict_keys = anonymize_dict_keys

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
            result = {}
            for key, value in data.items():
                new_value = self._recursive_anonymize(
                    value, depth + 1, field_name=f"{field_name}.{key}", **kwargs
                )
                new_key = key
                if self.anonymize_dict_keys and isinstance(key, str):
                    new_key = _unique_key(
                        self.anonymize_text(
                            key, field_name=f"{field_name}.{key}", **kwargs
                        ),
                        result,
                    )
                result[new_key] = new_value
            return result
        elif isinstance(data, (list, tuple)):
            items = [
                self._recursive_anonymize(
                    item, depth + 1, field_name=f"{field_name}.{i}", **kwargs
                )
                for i, item in enumerate(data)
            ]
            return items if isinstance(data, list) else tuple(items)
        else:
            return data


def _unique_key(key: str, existing: Dict[Any, Any]) -> str:
    """Keep keys distinct when several of them anonymize to the same text,
    e.g. two different e-mail addresses that both become ``[EMAIL]``."""
    if key not in existing:
        return key
    suffix = 2
    while f"{key} ({suffix})" in existing:
        suffix += 1
    return f"{key} ({suffix})"
