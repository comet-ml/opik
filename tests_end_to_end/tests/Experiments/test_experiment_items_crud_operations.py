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
import logging

logger = logging.getLogger(__name__)


class TestExperimentItemsCrud:
    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    def test_all_experiment_items_created(self, page: Page, mock_experiment):
        """Test experiment items creation and visibility in both UI and backend.

        Steps:
        1. Create experiment on dataset with 10 items (via mock_experiment fixture)
        2. Navigate to experiment's items page
        3. Verify UI item counter shows correct total (10 items)
        4. Verify backend trace_count matches dataset size
        5. Verify item IDs match between UI and backend stream endpoint
        """
        logger.info("Starting experiment items visibility test")
        experiment_name = mock_experiment["name"]
        expected_size = mock_experiment["size"]

        # Navigate to experiment items
        logger.info(f"Navigating to items page for experiment '{experiment_name}'")
        experiments_page = ExperimentsPage(page)
        try:
            experiments_page.go_to_page()
            experiments_page.click_first_experiment_that_matches_name(
                exp_name=experiment_name
            )
            logger.info("Successfully navigated to experiment items page")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to experiment items.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to experiment not found or navigation issues"
            ) from e

        # Verify items count in UI
        logger.info("Verifying items count in UI")
        experiment_items_page = ExperimentItemsPage(page)
        try:
            items_on_page = (
                experiment_items_page.get_total_number_of_items_in_experiment()
            )
            assert items_on_page == expected_size, (
                f"Items count mismatch in UI.\n"
                f"Expected: {expected_size}\n"
                f"Got: {items_on_page}"
            )
            logger.info(f"Successfully verified UI shows {items_on_page} items")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify items count in UI.\n"
                f"Experiment name: {experiment_name}\n"
                f"Expected count: {expected_size}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify backend trace count
        logger.info("Verifying experiment trace count via backend")
        try:
            experiment_backend = get_experiment_by_id(mock_experiment["id"])
            assert experiment_backend.trace_count == expected_size, (
                f"Trace count mismatch in backend.\n"
                f"Expected: {expected_size}\n"
                f"Got: {experiment_backend.trace_count}"
            )
            logger.info(f"Successfully verified backend shows {expected_size} traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify trace count via backend.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify item IDs match between UI and backend
        logger.info("Verifying item IDs match between UI and backend")
        try:
            ids_on_backend = [
                item["dataset_item_id"]
                for item in experiment_items_stream(experiment_name)
            ]
            ids_on_frontend = experiment_items_page.get_all_item_ids_in_experiment()

            assert Counter(ids_on_backend) == Counter(ids_on_frontend), (
                f"Item IDs mismatch between UI and backend.\n"
                f"Backend IDs: {sorted(ids_on_backend)}\n"
                f"Frontend IDs: {sorted(ids_on_frontend)}"
            )
            logger.info("Successfully verified item IDs match between UI and backend")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify item IDs match.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    def test_delete_experiment_items(self, page: Page, mock_experiment):
        """Test experiment item deletion and verification in both UI and backend.

        Steps:
        1. Create experiment on dataset with 10 items (via mock_experiment fixture)
        2. Navigate to experiment's items page
        3. Get one item ID from backend stream and delete it
        4. Verify UI counter updates to show one less item
        5. Verify backend trace_count updates
        6. Verify remaining item IDs match between UI and backend
        """
        logger.info("Starting experiment item deletion test")
        experiment_name = mock_experiment["name"]
        initial_size = mock_experiment["size"]

        # Navigate to experiment items
        logger.info(f"Navigating to items page for experiment '{experiment_name}'")
        experiments_page = ExperimentsPage(page)
        try:
            experiments_page.go_to_page()
            experiments_page.click_first_experiment_that_matches_name(
                exp_name=experiment_name
            )
            logger.info("Successfully navigated to experiment items page")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to experiment items.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Get and delete one item
        logger.info("Fetching item to delete")
        try:
            id_to_delete = experiment_items_stream(exp_name=experiment_name, limit=1)[
                0
            ]["id"]
            logger.info(f"Deleting experiment item with ID: {id_to_delete}")
            delete_experiment_items_by_id(ids=[id_to_delete])
            logger.info("Successfully deleted experiment item")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete experiment item.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify updated count in UI
        logger.info("Verifying updated items count in UI")
        experiment_items_page = ExperimentItemsPage(page)
        try:
            experiment_items_page.page.reload()
            items_on_page = (
                experiment_items_page.get_total_number_of_items_in_experiment()
            )
            assert items_on_page == initial_size - 1, (
                f"Items count incorrect after deletion.\n"
                f"Expected: {initial_size - 1}\n"
                f"Got: {items_on_page}"
            )
            logger.info(f"Successfully verified UI shows {items_on_page} items")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify updated items count in UI.\n"
                f"Experiment name: {experiment_name}\n"
                f"Expected count: {initial_size - 1}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify updated count in backend
        logger.info("Verifying updated trace count via backend")
        try:
            experiment_sdk = get_experiment_by_id(mock_experiment["id"])
            assert experiment_sdk.trace_count == initial_size - 1, (
                f"Trace count incorrect after deletion.\n"
                f"Expected: {initial_size - 1}\n"
                f"Got: {experiment_sdk.trace_count}"
            )
            logger.info("Successfully verified updated trace count in backend")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify updated trace count via backend.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify remaining IDs match
        logger.info("Verifying remaining item IDs match between UI and backend")
        try:
            ids_on_backend = [
                item["dataset_item_id"]
                for item in experiment_items_stream(experiment_name)
            ]
            ids_on_frontend = experiment_items_page.get_all_item_ids_in_experiment()

            assert Counter(ids_on_backend) == Counter(ids_on_frontend), (
                f"Remaining item IDs mismatch between UI and backend.\n"
                f"Backend IDs: {sorted(ids_on_backend)}\n"
                f"Frontend IDs: {sorted(ids_on_frontend)}"
            )
            logger.info("Successfully verified remaining item IDs match")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify remaining item IDs match.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}"
            ) from e
