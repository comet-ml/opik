import abc
from typing import Dict, Any, Union, List

AnonymizerDataType = Union[Dict[str, Any], str, List[Any]]


class Anonymizer(abc.ABC):
    """Abstract base class for anonymizing sensitive data in various data structures."""

    @abc.abstractmethod
    def anonymize(self, data: AnonymizerDataType, **kwargs: Any) -> AnonymizerDataType:
        pass
