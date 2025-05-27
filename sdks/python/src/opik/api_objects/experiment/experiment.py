import functools
import logging
from typing import List, Optional

import opik.rest_api
from opik.message_processing.batching import sequence_splitter
from opik.rest_api import client as rest_api_client
from opik.rest_api.types import experiment_item as rest_experiment_item
from . import experiment_item
from .. import constants, helpers, rest_stream_parser
from ...api_objects.prompt import Prompt

LOGGER = logging.getLogger(__name__)


class Experiment:
    def __init__(
        self,
        id: str,
        name: Optional[str],
        dataset_name: str,
        rest_client: rest_api_client.OpikApi,
        prompts: Optional[List[Prompt]] = None,
    ) -> None:
        self._id = id
        self._name = name
        self._dataset_name = dataset_name
        self._rest_client = rest_client
        self._prompts = prompts

    @property
    def id(self) -> str:
        return self._id

    @functools.cached_property
    def dataset_id(self) -> str:
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._dataset_name
        ).id

    @property
    def dataset_name(self) -> str:
        return self._dataset_name

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

    def get_items(
        self,
        max_results: Optional[int] = None,
        truncate: bool = False,
    ) -> List[experiment_item.ExperimentItemContent]:
        """
        Retrieves and returns a list of experiment items by streaming from the backend in batches, with an option to
        truncate the results for each batch.

        This method streams experiment items from a backend service in chunks up to the specified `max_results`
        or until the available items are exhausted. It handles batch-wise retrieval and parsing, ensuring the client
        receives a list of `ExperimentItemContent` objects, while respecting the constraints on maximum retrieval size
        from the backend. If truncation is enabled, the backend may return truncated details for each item.

        Args:
            max_results: Maximum number of experiment items to retrieve.
            truncate: Whether to truncate the items returned by the backend.

        """
        result: List[experiment_item.ExperimentItemContent] = []
        max_endpoint_batch_size = rest_stream_parser.MAX_ENDPOINT_BATCH_SIZE

        while True:
            if max_results is None:
                current_batch_size = max_endpoint_batch_size
            else:
                current_batch_size = min(
                    max_results - len(result), max_endpoint_batch_size
                )

            items_stream = self._rest_client.experiments.stream_experiment_items(
                experiment_name=self.name,
                limit=current_batch_size,
                last_retrieved_id=result[-1].id if len(result) > 0 else None,
                truncate=truncate,
            )

            experiment_item_compare_current_batch = (
                rest_stream_parser.read_and_parse_stream(
                    stream=items_stream,
                    item_class=opik.rest_api.ExperimentItemCompare,
                )
            )

            for item in experiment_item_compare_current_batch:
                converted_item = experiment_item.ExperimentItemContent.from_rest_experiment_item_compare(
                    value=item
                )
                result.append(converted_item)

            if current_batch_size > len(experiment_item_compare_current_batch):
                break

        return result
