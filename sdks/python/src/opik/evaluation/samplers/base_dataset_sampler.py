import abc
from typing import List


from opik.api_objects.dataset import dataset_item


class BaseDatasetSampler(abc.ABC):
    """
    Defines the BaseDatasetSampler for sampling dataset items.

    This is an abstract base class that provides the definition
    for dataset sampling. It requires implementation of the `sample`
    method in subclasses, which specifies the sampling logic tailored
    to specific needs.

    Methods in this class are enforced to be redefined in any
    concrete implementation.

    """

    @abc.abstractmethod
    def sample(
        self, data_item: List[dataset_item.DatasetItem]
    ) -> List[dataset_item.DatasetItem]:
        pass
