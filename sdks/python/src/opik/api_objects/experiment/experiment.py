import logging
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
        self.id = id
        self.name = name
        self.dataset_name = dataset_name
        self._rest_client = rest_client
        self.prompt = prompt

    def insert(self, experiment_items: List[experiment_item.ExperimentItem]) -> None:
        rest_experiment_items = [
            rest_experiment_item.ExperimentItem(
                id=item.id if item.id is not None else helpers.generate_id(),
                experiment_id=self.id,
                dataset_item_id=item.dataset_item_id,
                trace_id=item.trace_id,
            )
            for item in experiment_items
        ]

        batches = sequence_splitter.split_into_batches(
            rest_experiment_items, max_length=constants.EXPERIMENT_ITEMS_MAX_BATCH_SIZE
        )

        for batch in batches:
            LOGGER.debug("Sending experiment items batch: %s", batch)
            self._rest_client.experiments.create_experiment_items(
                experiment_items=batch,
            )
