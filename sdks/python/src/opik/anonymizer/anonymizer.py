import abc
from typing import Any, Union

AnonymizerDataType = Union[dict[str, Any], str, list[Any]]


class Anonymizer(abc.ABC):
    """Abstract base class for anonymizing sensitive data in various data structures."""

    @abc.abstractmethod
    def anonymize(self, data: AnonymizerDataType, **kwargs: Any) -> AnonymizerDataType:
        pass
