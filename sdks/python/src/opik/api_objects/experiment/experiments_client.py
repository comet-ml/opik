import json
from typing import List, Optional

from . import rest_operations, experiment_item
from .. import opik_query_language
from ...rest_api import client as rest_api_client


class ExperimentsClient:
    """Client for managing and querying experiments in bulk."""

    def __init__(self, rest_client: rest_api_client.OpikApi):
        self._rest_client = rest_client

    def find_experiment_items_for_dataset(
        self,
        dataset_name: str,
        experiment_ids: List[str],
        truncate: bool,
        max_results: int,
        filter_string: Optional[str] = None,
    ) -> List[experiment_item.ExperimentItemContent]:
        # prepare filter expression
        if filter_string is not None:
            filter_expression = json.dumps(
                opik_query_language.OpikQueryLanguage(
                    filter_string
                ).get_filter_expressions()
            )
        else:
            filter_expression = None

        # get dataset id
        dataset_id = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name
        ).id

        return rest_operations.find_experiment_items_for_dataset(
            dataset_id=dataset_id,
            experiment_ids=experiment_ids,
            rest_client=self._rest_client,
            max_results=max_results,
            truncate=truncate,
            filter_expression=filter_expression,
        )
