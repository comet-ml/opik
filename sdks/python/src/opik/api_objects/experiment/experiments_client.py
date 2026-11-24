import json
from typing import Callable, List, Optional

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
        experiment_ids: Optional[List[str]] = None,
        truncate: bool = True,
        max_results: int = 1000,
        filter_string: Optional[str] = None,
        project_name: Optional[str] = None,
        *,
        dataset_versions: Optional[List[str]] = None,
        page_size: int = constants.EXPERIMENT_ITEMS_READ_PAGE_SIZE,
        num_threads: int = constants.DATASET_ITEMS_READ_NUM_THREADS,
    ) -> List[experiment_item.ExperimentItemContent]:
        """
        Find experiment items associated with a specific dataset.

        With ``experiment_ids``, returns those experiments' items. Without them,
        returns the items of every regular experiment on the dataset (optimizer
        trial, mini-batch and mutation experiments are excluded), grouped by
        dataset item, so ``filter_string='id = "<dataset item id>"'`` returns one
        item's results across experiments. Each result carries its
        ``experiment_id``.

        This method queries the dataset for experiment items matching the
        criteria provided by the input parameters. It leverages the
        ExperimentsClient to perform the underlying operation.

        Args:
            dataset_name: Name of the dataset to query for experiment items.
            experiment_ids: Experiment IDs to read. Omit to read every regular
                experiment on the dataset.
            filter_string: Optional filter string to refine the query. With
                ``experiment_ids``, it accepts dataset fields, feedback scores,
                etc. Without them, it accepts dataset item fields only, such as
                ``id``.
            truncate: Whether to truncate image data stored in input, output,
                or metadata. Defaults to True.
            max_results: Maximum number of results to return. Defaults to 1000.
            project_name: Optional project name to associate with the query. If not provided, the default project will be used.
            dataset_versions: Dataset version hashes or tags. Only experiments run
                against one of these versions are included. Without it, an item's
                results span every version of that item, whose content may differ
                between versions. Requires ``experiment_ids`` to be omitted.
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
                ``constants.DATASET_ITEMS_READ_MAX_THREADS``, or if both
                ``experiment_ids`` and ``dataset_versions`` are given.
            DatasetVersionNotFound: If a version in ``dataset_versions`` does not
                exist.

        Note:
            Without ``experiment_ids``, ``page_size`` and ``num_threads`` do not
            control paging (the read pages through a server-side cursor), but
            they are still validated. Results are read experiment by experiment,
            so a read cut short by ``max_results`` can miss later experiments
            entirely; a warning is logged when that happens. Either way, each
            result's ``dataset_item_data`` is the dataset item's content, taken
            from the newest version among the experiments read.

        Note:
            To look at these results without writing code, AI coding assistants
            connected to the Opik MCP server can call
            ``list("dataset_item", experiment_ids=[...])``, which compares the
            experiments case by case.
            See https://www.comet.com/docs/opik/mcp-server
        """
        validation_helpers.validate_bounded_positive_int(
            page_size, "page_size", constants.EXPERIMENT_ITEMS_READ_MAX_PAGE_SIZE
        )
        validation_helpers.validate_bounded_positive_int(
            num_threads, "num_threads", constants.DATASET_ITEMS_READ_MAX_THREADS
        )

        # The paged path takes explicit experiments, each run against one version.
        if experiment_ids is not None and dataset_versions is not None:
            raise ValueError(
                "dataset_versions can only be used when experiment_ids is omitted."
            )

        dataset_id = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name, project_name=project_name
        ).id

        if experiment_ids is None:
            return rest_operations.stream_experiment_items_for_dataset(
                rest_client=self._rest_client,
                dataset_id=dataset_id,
                dataset_versions=dataset_versions,
                max_results=max_results,
                truncate=truncate,
                filter_expression=_filter_expression(
                    filter_string,
                    opik_query_language.OpikQueryLanguage.for_dataset_items,
                ),
            )

        return rest_operations.find_experiment_items_for_dataset(
            dataset_id=dataset_id,
            experiment_ids=experiment_ids,
            rest_client=self._rest_client,
            max_results=max_results,
            truncate=truncate,
            filter_expression=_filter_expression(
                filter_string, opik_query_language.OpikQueryLanguage.for_traces
            ),
            page_size=page_size,
            num_threads=num_threads,
        )


def _filter_expression(
    filter_string: Optional[str],
    parse: Callable[[Optional[str]], opik_query_language.OpikQueryLanguage],
) -> Optional[str]:
    if filter_string is None:
        return None
    return json.dumps(parse(filter_string).get_filter_expressions())
