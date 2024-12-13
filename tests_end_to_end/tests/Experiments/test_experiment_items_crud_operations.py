import pytest
from playwright.sync_api import Page
from page_objects.ExperimentsPage import ExperimentsPage
from page_objects.ExperimentItemsPage import ExperimentItemsPage
from sdk_helpers import (
    get_experiment_by_id,
    delete_experiment_items_by_id,
    experiment_items_stream,
)
from collections import Counter


class TestExperimentItemsCrud:
    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    def test_all_experiment_items_created(self, page: Page, mock_experiment):
        """
        Creates an experiment with 10 experiment items, then checks that all items are visible in both UI and backend
        1. Create an experiment on a dataset with 10 items (mock_experiment fixture)
        2. Check the item counter on the UI displays the correct total (10 items)
        3. Check the 'trace_count' parameter of the experiment as returned via the v1/private/experiments/{id} endpoint
        matches the size of the dataset (10 items)
        4. Check the list of IDs displayed in the UI (currently dataset item IDs) perfectly matches the list of dataset item IDs
        as returned from the v1/private/experiments/items/stream endpoint (easy change to grab the items via the SDK if we ever add this)
        """
        experiments_page = ExperimentsPage(page)
        experiments_page.go_to_page()
        experiments_page.click_first_experiment_that_matches_name(
            exp_name=mock_experiment["name"]
        )

        experiment_items_page = ExperimentItemsPage(page)
        items_on_page = experiment_items_page.get_total_number_of_items_in_experiment()
        assert items_on_page == mock_experiment["size"]

        experiment_backend = get_experiment_by_id(mock_experiment["id"])
        assert experiment_backend.trace_count == mock_experiment["size"]

        ids_on_backend = [
            item["dataset_item_id"]
            for item in experiment_items_stream(mock_experiment["name"])
        ]
        ids_on_frontend = experiment_items_page.get_all_item_ids_in_experiment()

        assert Counter(ids_on_backend) == Counter(ids_on_frontend)

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    def test_delete_experiment_items(self, page: Page, mock_experiment):
        """
        Deletes a single experiment item and checks that everything gets updated on both the UI and the backend
        1. Create an experiment on a dataset with 10 items (mock_experiment fixture)
        2. Grabbing an experiment ID from the v1/private/experiments/items/stream endpoint, send a delete request to delete
        a single experiment item from the experiment
        3. Check the item counter in the UI is updated (to size(initial_experiment) - 1)
        4. Check the 'trace_count' parameter of the experiment as returned via the v1/private/experiments/{id} endpoint
        is updated to the new size (as above)
        5. Check the list of IDs displayed in the UI (currently dataset item IDs) perfectly matches the list of dataset item IDs
        as returned from the v1/private/experiments/items/stream endpoint (easy change to grab the items via the SDK if we ever add this)
        """
        experiments_page = ExperimentsPage(page)
        experiments_page.go_to_page()
        experiments_page.click_first_experiment_that_matches_name(
            exp_name=mock_experiment["name"]
        )

        id_to_delete = experiment_items_stream(
            exp_name=mock_experiment["name"], limit=1
        )[0]["id"]
        delete_experiment_items_by_id(ids=[id_to_delete])

        experiment_items_page = ExperimentItemsPage(page)
        experiment_items_page.page.reload()
        items_on_page = experiment_items_page.get_total_number_of_items_in_experiment()
        assert items_on_page == mock_experiment["size"] - 1

        experiment_sdk = get_experiment_by_id(mock_experiment["id"])
        assert experiment_sdk.trace_count == mock_experiment["size"] - 1

        ids_on_backend = [
            item["dataset_item_id"]
            for item in experiment_items_stream(mock_experiment["name"])
        ]
        ids_on_frontend = experiment_items_page.get_all_item_ids_in_experiment()

        assert Counter(ids_on_backend) == Counter(ids_on_frontend)
