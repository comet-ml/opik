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
        """
        Samples and filters a list of dataset items according to a specific implementation.

        Args:
            data_item (List[dataset_item.DatasetItem]): A list of DatasetItem objects to be
                sampled and filtered.

        Returns:
            List[dataset_item.DatasetItem]: A list of DatasetItem objects resulting
                from the sampling process.

        Raises:
            NotImplementedError: If the method is not implemented in a subclass.
        """
        pass
