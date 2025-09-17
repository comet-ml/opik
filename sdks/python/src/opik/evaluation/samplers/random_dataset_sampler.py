import random
from typing import List, Optional

from opik.api_objects.dataset import dataset_item

from . import base_dataset_sampler


class RandomDatasetSampler(base_dataset_sampler.BaseDatasetSampler):
    def __init__(
        self, max_samples: int, shuffle: bool = True, seed: Optional[int] = None
    ) -> None:
        """Samples a random subset of dataset items.

        This class is a dataset sampler that selects a random subset of items from a dataset.
        The number of items to sample can be specified, and shuffling can be enabled or disabled.
        An optional random seed can be provided for reproducibility.

        Args:
            max_samples: The maximum number of samples to generate.
            shuffle: Whether to shuffle the samples. Default is True, False provides a speedup
                for large datasets.
            seed: Seed for the random number generator. If None, then fresh, unpredictable
                entropy will be pulled from the OS.
        """
        self.max_samples = max_samples
        self.shuffle = shuffle
        self.seed = seed

    def sample(
        self, data_items: List[dataset_item.DatasetItem]
    ) -> List[dataset_item.DatasetItem]:
        if len(data_items) == 0:
            return []

        # Create a random number generator with the specified seed
        rng = random.Random(self.seed)

        # Determine how many samples to take
        sample_size = min(len(data_items), self.max_samples)

        # Do sample first to avoid shuffling the entire dataset
        items = rng.sample(data_items, sample_size)

        if self.shuffle:
            rng.shuffle(items)

        return items
