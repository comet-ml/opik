import abc
import logging
from typing import Any, Optional

from . import anonymizer

logger = logging.getLogger(__name__)

MAX_DEPTH_PLACEHOLDER = "[max depth reached]"


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
        self,
        data: anonymizer.AnonymizerDataType,
        max_depth: Optional[int] = None,
        **kwargs: Any,
    ) -> anonymizer.AnonymizerDataType:
        """Anonymize ``data``, optionally overriding the configured depth cap.

        The per-call override exists for the SDK's own encoding path: it has
        already resolved cycles, so it can allow payloads far deeper than the
        conservative default meant for direct calls.
        """
        return self._recursive_anonymize(data, max_depth=max_depth, **kwargs)

    @abc.abstractmethod
    def anonymize_text(self, data: str, **kwargs: Any) -> str:
        pass

    def _recursive_anonymize(
        self,
        data: anonymizer.AnonymizerDataType,
        depth: int = 0,
        field_name: Optional[str] = None,
        max_depth: Optional[int] = None,
        **kwargs: Any,
    ) -> anonymizer.AnonymizerDataType:
        effective_max_depth = self.max_depth if max_depth is None else max_depth
        if depth >= effective_max_depth:
            # Never return the raw subtree here: it may contain PII, and this
            # method's contract is that only anonymized data reaches Opik.
            logger.warning(
                "Anonymizer reached max_depth=%d; replacing the remaining "
                "subtree with %r so it is not stored raw. Raise max_depth if "
                "this payload is legitimate.",
                effective_max_depth,
                MAX_DEPTH_PLACEHOLDER,
            )
            return MAX_DEPTH_PLACEHOLDER

        if field_name is None:
            field_name = ""

        if isinstance(data, str):
            return self.anonymize_text(data, field_name=field_name, **kwargs)
        elif isinstance(data, dict):
            return {
                key: self._recursive_anonymize(
                    value,
                    depth + 1,
                    field_name=f"{field_name}.{key}",
                    max_depth=effective_max_depth,
                    **kwargs,
                )
                for key, value in data.items()
            }
        elif isinstance(data, list):
            return [
                self._recursive_anonymize(
                    item,
                    depth + 1,
                    field_name=f"{field_name}.{i}",
                    max_depth=effective_max_depth,
                    **kwargs,
                )
                for i, item in enumerate(data)
            ]
        else:
            return data
