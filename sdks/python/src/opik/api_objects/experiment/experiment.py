import logging
import functools
from typing import List, Optional

from opik.rest_api import client as rest_api_client
from opik.rest_api.types import experiment_item as rest_experiment_item
from opik.message_processing.batching import sequence_splitter

from . import experiment_item
from .. import helpers, constants
from ... import Prompt

LOGGER = logging.getLogger(__name__)


class Experiment:
    def __init__(
        self,
        id: str,
        name: Optional[str],
        dataset_name: str,
        rest_client: rest_api_client.OpikApi,
        prompt: Optional[Prompt] = None,
    ) -> None:
        self._id = id
        self._name = name
        self._dataset_name = dataset_name
        self._rest_client = rest_client
        self._prompt = prompt

    @property
    def id(self) -> str:
        return self._id

    @functools.cached_property
    def dataset_id(self) -> str:
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._dataset_name
        ).id

    @functools.cached_property
    def name(self) -> str:
        if self._name is not None:
            return self._name

        return self._rest_client.experiments.get_experiment_by_id(id=self.id).name

    def insert(
        self,
        experiment_items_references: List[experiment_item.ExperimentItemReferences],
    ) -> None:
        """
        Creates a new experiment item by linking the existing trace and dataset item.

        Args:
            experiment_items_references: The list of ExperimentItemReferences objects, containing
                trace id and dataset item id to link together into experiment item.

        Returns:
            None
        """
        rest_experiment_items = [
            rest_experiment_item.ExperimentItem(
                id=helpers.generate_id(),
                experiment_id=self.id,
                dataset_item_id=item.dataset_item_id,
                trace_id=item.trace_id,
            )
            for item in experiment_items_references
        ]

        batches = sequence_splitter.split_into_batches(
            rest_experiment_items, max_length=constants.EXPERIMENT_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Sending experiment items batch: %s", batch)
            self._rest_client.experiments.create_experiment_items(
                experiment_items=batch,
            )
            LOGGER.debug("Sent experiment items batch of size %d", len(batch))

    def get_items(self) -> List[experiment_item.ExperimentItemContent]:
        """
        Returns:
            List[ExperimentItemContent]: the list with contents of existing experiment items.
        """
        result: List[experiment_item.ExperimentItemContent] = []

        page = 0

        while True:  # TODO: refactor this logic when backend implements a proper streaming endpoint
            page += 1
            dataset_items_page = (
                self._rest_client.datasets.find_dataset_items_with_experiment_items(
                    id=self.dataset_id,
                    experiment_ids=f'["{self.id}"]',
                    page=page,
                    size=100,
                )
            )
            if len(dataset_items_page.content) == 0:
                break

            for dataset_item in dataset_items_page.content:
                rest_experiment_item_compare = dataset_item.experiment_items[0]
                content = experiment_item.ExperimentItemContent.from_rest_experiment_item_compare(
                    value=rest_experiment_item_compare
                )
                result.append(content)

        return result
