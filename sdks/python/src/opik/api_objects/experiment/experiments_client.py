import json
from typing import List, Optional

from . import rest_operations, experiment_item
from .. import constants, opik_query_language, validation_helpers
from ...rest_api import client as rest_api_client


class ExperimentsClient:
    """Client for managing and querying experiments in bulk."""

    def __init__(self, rest_client: rest_api_client.OpikApi):
        self._rest_client = rest_client

    def find_experiment_items_for_dataset(
        self,
        dataset_name: str,
        experiment_ids: List[str],
        truncate: bool = True,
        max_results: int = 1000,
        filter_string: Optional[str] = None,
        project_name: Optional[str] = None,
        page_size: int = constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE,
        num_threads: int = constants.DATASET_ITEMS_READ_NUM_THREADS,
    ) -> List[experiment_item.ExperimentItemContent]:
        """
        Find experiment items associated with a specific dataset among a list of experiments.

        This method queries the dataset for experiment items matching the
        criteria provided by the input parameters. It leverages the
        ExperimentsClient to perform the underlying operation.

        Args:
            dataset_name: Name of the dataset to query for experiment items.
            experiment_ids: List of experiment IDs to filter the results.
            filter_string: Optional filter string to refine the
                query based on additional criteria (dataset fields, feedback scores, etc.).
            truncate: Whether to truncate image data stored in input, output,
                or metadata. Defaults to True.
            max_results: Maximum number of results to return. Defaults to 1000.
            project_name: Optional project name to associate with the query. If not provided, the default project will be used.
            page_size: Number of dataset items requested per page. Must be a
                positive integer not exceeding
                ``constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE``. Trades request
                count against per-request size; the read is round-trip bound, so
                lowering it is slower.
            num_threads: Number of pages fetched concurrently after the first
                one. Must be a positive integer not exceeding
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``. Pass ``1`` to read
                sequentially.

        Returns:
            A list of experiment item content objects that match the criteria.

        Raises:
            ValueError: If ``page_size`` is not a positive integer or exceeds
                ``constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE``, or if
                ``num_threads`` is not a positive integer or exceeds
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``.
        """
        validation_helpers.validate_bounded_positive_int(
            page_size, "page_size", constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
        )
        validation_helpers.validate_bounded_positive_int(
            num_threads, "num_threads", constants.DATASET_ITEMS_READ_MAX_THREADS
        )

        # prepare filter expression
        if filter_string is not None:
            filter_expression = json.dumps(
                opik_query_language.OpikQueryLanguage.for_traces(
                    filter_string
                ).get_filter_expressions()
            )
        else:
            filter_expression = None

        # get dataset id
        dataset_id = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name, project_name=project_name
        ).id

        return rest_operations.find_experiment_items_for_dataset(
            dataset_id=dataset_id,
            experiment_ids=experiment_ids,
            rest_client=self._rest_client,
            max_results=max_results,
            truncate=truncate,
            filter_expression=filter_expression,
            page_size=page_size,
            num_threads=num_threads,
        )
