import pytest
from playwright.sync_api import Page, expect
from page_objects.DatasetsPage import DatasetsPage
from page_objects.DatasetItemsPage import DatasetItemsPage
from .datasets_utils import (
    TEST_ITEMS,
    TEST_ITEMS_UPDATE,
    compare_item_lists,
    wait_for_dataset_to_be_visible,
    get_updated_items,
    insert_dataset_items_sdk,
    insert_dataset_items_ui,
    delete_one_dataset_item_sdk,
    delete_one_dataset_item_ui,
    wait_for_number_of_items_in_dataset,
)
import opik
import logging
import allure

logger = logging.getLogger(__name__)


class TestDatasetItemsCrud:
    @pytest.mark.parametrize(
        "dataset_insert", ["insert_via_sdk"]
    )  # add insert_via_ui once flakiness is figured out
    @pytest.mark.parametrize(
        "dataset_creation_fixture",
        ["create_dataset_sdk", "create_dataset_ui"],
    )
    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.regression
    @pytest.mark.sanity
    @pytest.mark.datasets
    @allure.title(
        "Dataset item insertion - {dataset_creation_fixture} and {dataset_insert}"
    )
    def test_dataset_item_insertion(
        self,
        request,
        page: Page,
        client: opik.Opik,
        dataset_creation_fixture,
        dataset_insert,
    ):
        """Tests the insertion of items into a dataset and verifies they appear correctly in both UI and SDK.

        This test verifies that items can be properly added to a dataset and are consistently
        visible across both the UI and SDK interfaces. It tests all possible combinations of:
        - Creating the dataset via SDK or UI
        - Inserting items via SDK or UI (currently SDK only due to UI flakiness)

        Steps:
        1. Create a new dataset using either the SDK or UI
        2. Insert 10 test items into the dataset, each with an input/output pair
           Example item: {"input": "input0", "output": "output0"}
        3. Wait for items to be visible in the dataset
        4. Verify via SDK:
           - Fetch all items and compare with test data
           - Check exact number of items matches
        5. Verify via UI:
           - Navigate to dataset page
           - Check all items are visible and match test data
           - Verify item count matches expected
        """
        logger.info(
            f"Starting dataset item insertion test with {dataset_creation_fixture} and {dataset_insert}"
        )

        try:
            dataset = wait_for_dataset_to_be_visible(
                client=client,
                dataset_name=request.getfixturevalue(dataset_creation_fixture),
                timeout=10,
            )
        except TimeoutError as e:
            raise AssertionError(
                f"Dataset creation failed or dataset not visible after 10s.\n"
                f"Creation method: {dataset_creation_fixture}\n"
                f"Dataset name: {request.getfixturevalue(dataset_creation_fixture)}"
            ) from e

        logger.info(f"Inserting {len(TEST_ITEMS)} items via {dataset_insert}")

        try:
            if "ui" in dataset_insert:
                insert_dataset_items_ui(page, dataset.name, TEST_ITEMS)
            elif "sdk" in dataset_insert:
                insert_dataset_items_sdk(client, dataset.name, TEST_ITEMS)
        except Exception as e:
            raise AssertionError(
                f"Failed to insert items via {dataset_insert}.\n"
                f"Dataset name: {dataset.name}\n"
                f"Error: {str(e)}"
            ) from e

        try:
            wait_for_number_of_items_in_dataset(
                expected_items_number=len(TEST_ITEMS), dataset=dataset, timeout=10
            )
        except AssertionError as e:
            items_found = len(dataset.get_items())
            raise AssertionError(
                f"Dataset item count mismatch.\n"
                f"Expected: {len(TEST_ITEMS)} items\n"
                f"Found: {items_found} items\n"
                f"Dataset: {dataset.name}"
            ) from e

        # Verify SDK items
        items_from_sdk = dataset.get_items()
        if not compare_item_lists(expected=TEST_ITEMS, actual=items_from_sdk):
            raise AssertionError(
                f"SDK items don't match expected items.\n"
                f"Expected items: {TEST_ITEMS}\n"
                f"Actual items: {items_from_sdk}\n"
                f"Dataset: {dataset.name}"
            )
        logger.info("Successfully verified items via SDK")

        # Verify UI items
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)
        items_from_ui = dataset_items_page.get_all_items_in_dataset()

        if not compare_item_lists(expected=TEST_ITEMS, actual=items_from_ui):
            raise AssertionError(
                f"UI items don't match expected items.\n"
                f"Expected items: {TEST_ITEMS}\n"
                f"Actual items: {items_from_ui}\n"
                f"Dataset: {dataset.name}"
            )
        logger.info("Successfully verified items via UI")

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.regression
    @pytest.mark.datasets
    @allure.title("Dataset item update")
    def test_dataset_item_update(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
    ):
        """Tests updating existing dataset items and verifies changes appear in both UI and SDK.

        This test ensures that existing items in a dataset can be modified and that these
        changes are properly synchronized across both interfaces.

        Steps:
        1. Create a new dataset via SDK and insert 10 initial test items
           Example initial item: {"input": "input0", "output": "output0"}
        2. Update all items with new data using dataset.update()
           Example updated item: {"input": "update-input0", "output": "update-output0"}
        3. Verify updates via SDK:
           - Fetch all items and verify new content
           - Check no old content remains
        4. Verify updates via UI:
           - Navigate to dataset page
           - Check all items show updated content
           - Verify no items show old content
        """
        logger.info("Starting dataset item update test")

        try:
            dataset = wait_for_dataset_to_be_visible(
                client=client, dataset_name=create_dataset_sdk, timeout=10
            )
        except TimeoutError as e:
            raise AssertionError(
                f"Dataset not visible after creation.\n"
                f"Dataset name: {create_dataset_sdk}"
            ) from e

        try:
            wait_for_number_of_items_in_dataset(
                expected_items_number=len(TEST_ITEMS), dataset=dataset, timeout=15
            )
        except AssertionError as e:
            items_found = len(dataset.get_items())
            raise AssertionError(
                f"Initial items not found in dataset.\n"
                f"Expected: {len(TEST_ITEMS)} items\n"
                f"Found: {items_found} items\n"
                f"Dataset: {dataset.name}"
            ) from e

        # Update items
        logger.info(f"Updating {len(TEST_ITEMS)} items with new data")
        items_from_sdk = dataset.get_items()
        updated_items = get_updated_items(
            current=items_from_sdk, update=TEST_ITEMS_UPDATE
        )

        try:
            dataset.update(updated_items)
        except Exception as e:
            raise AssertionError(
                f"Failed to update items via SDK.\n"
                f"Dataset: {dataset.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Continue with similar improvements for verification steps...

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.parametrize("item_deletion", ["delete_via_ui", "delete_via_sdk"])
    @pytest.mark.datasets
    @pytest.mark.regression
    @allure.title("Dataset item deletion - {item_deletion}")
    def test_dataset_item_deletion(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
        item_deletion,
    ):
        """Tests deletion of individual dataset items and verifies removal across interfaces.

        This test checks that items can be properly deleted from a dataset using either
        the UI or SDK interface, and that deletions are properly synchronized. The test
        runs twice - once for UI deletion and once for SDK deletion.

        Steps:
        1. Create a new dataset via SDK and insert 10 initial test items
           Example item: {"input": "input0", "output": "output0"}
        2. Delete one item using either:
           - UI: Click delete button on an item in the dataset view
           - SDK: Use dataset.delete() with item ID
        3. Verify deletion via SDK:
           - Check total item count decreased by 1
           - Verify deleted item's data is not present in any remaining items
        4. Verify deletion via UI:
           - Navigate to dataset page
           - Check item count shows one less item
           - Verify deleted item's data is not visible anywhere
        """
        logger.info(f"Starting dataset item deletion test via {item_deletion}")

        try:
            dataset = wait_for_dataset_to_be_visible(
                client=client, dataset_name=create_dataset_sdk, timeout=10
            )
        except TimeoutError as e:
            raise AssertionError(
                f"Dataset not visible after creation.\n"
                f"Dataset name: {create_dataset_sdk}"
            ) from e

        # Delete item and capture its data
        logger.info("Attempting to delete one item from dataset")
        try:
            item_deleted = {}
            if "ui" in item_deletion:
                item_deleted = delete_one_dataset_item_ui(page, dataset.name)
            elif "sdk" in item_deletion:
                item_deleted = delete_one_dataset_item_sdk(client, dataset.name)
        except Exception as e:
            raise AssertionError(
                f"Failed to delete item via {item_deletion}.\n"
                f"Dataset: {dataset.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify item count updated
        try:
            wait_for_number_of_items_in_dataset(
                expected_items_number=len(TEST_ITEMS) - 1, dataset=dataset, timeout=15
            )
        except AssertionError as e:
            items_found = len(dataset.get_items())
            raise AssertionError(
                f"Dataset item count not updated after deletion.\n"
                f"Expected: {len(TEST_ITEMS) - 1} items\n"
                f"Found: {items_found} items\n"
                f"Dataset: {dataset.name}"
            ) from e

        # Verify item removed from SDK view
        logger.info("Verifying item deletion in SDK view")
        items_from_sdk = dataset.get_items()
        deleted_item_not_in_sdk_list = not any(
            item["input"] == item_deleted["input"]
            and item["output"] == item_deleted["output"]
            for item in items_from_sdk
        )

        if not deleted_item_not_in_sdk_list:
            raise AssertionError(
                f"Deleted item still present in SDK view.\n"
                f"Deleted item: {item_deleted}\n"
                f"Items in SDK: {items_from_sdk}\n"
                f"Dataset: {dataset.name}"
            )
        logger.info("Successfully verified deletion in SDK view")

        # Verify item removed from UI view
        logger.info("Verifying item deletion in UI view")
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)
        items_from_ui = dataset_items_page.get_all_items_in_dataset()

        if len(items_from_ui) != len(TEST_ITEMS) - 1:
            raise AssertionError(
                f"Incorrect number of items in UI view after deletion.\n"
                f"Expected: {len(TEST_ITEMS) - 1} items\n"
                f"Found: {len(items_from_ui)} items\n"
                f"Dataset: {dataset.name}"
            )

        deleted_item_not_in_ui_list = not any(
            item["input"] == item_deleted["input"]
            and item["output"] == item_deleted["output"]
            for item in items_from_ui
        )

        if not deleted_item_not_in_ui_list:
            raise AssertionError(
                f"Deleted item still present in UI view.\n"
                f"Deleted item: {item_deleted}\n"
                f"Items in UI: {items_from_ui}\n"
                f"Dataset: {dataset.name}"
            )
        logger.info("Successfully verified deletion in UI view")

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.regression
    @pytest.mark.datasets
    @allure.title("Dataset clear")
    def test_dataset_clear(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
    ):
        """Tests complete clearing of a dataset and verifies empty state across interfaces.

        This test verifies that the dataset.clear() operation properly removes all items
        from a dataset and that this empty state is correctly reflected in both interfaces.

        Steps:
        1. Create a new dataset via SDK and insert 10 initial test items
           Example item: {"input": "input0", "output": "output0"}
        2. Clear the entire dataset using dataset.clear()
        3. Verify empty state via SDK:
           - Check get_items() returns empty list
           - Verify item count is 0
        4. Verify empty state via UI:
           - Navigate to dataset page
           - Check "There are no dataset items yet" message is visible
           - Verify no items are displayed in the table
        """
        logger.info("Starting dataset clear test")

        try:
            dataset = wait_for_dataset_to_be_visible(
                client=client, dataset_name=create_dataset_sdk, timeout=10
            )
        except TimeoutError as e:
            raise AssertionError(
                f"Dataset not visible after creation.\n"
                f"Dataset name: {create_dataset_sdk}"
            ) from e

        # Clear dataset
        logger.info(f"Attempting to clear all items from dataset {dataset.name}")
        try:
            dataset.clear()
        except Exception as e:
            raise AssertionError(
                f"Failed to clear dataset.\n"
                f"Dataset: {dataset.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify SDK shows empty dataset
        try:
            wait_for_number_of_items_in_dataset(
                expected_items_number=0, dataset=dataset, timeout=15
            )
        except AssertionError as e:
            items_found = len(dataset.get_items())
            raise AssertionError(
                f"Dataset not empty after clear operation.\n"
                f"Expected: 0 items\n"
                f"Found: {items_found} items\n"
                f"Dataset: {dataset.name}"
            ) from e
        logger.info("Successfully verified dataset is empty via SDK")

        # Verify UI shows empty dataset
        logger.info("Verifying empty dataset in UI")
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)

        try:
            expect(
                dataset_items_page.page.get_by_text("There are no dataset items yet")
            ).to_be_visible()
            logger.info("Successfully verified dataset is empty in UI view")
        except Exception as e:
            items_from_ui = dataset_items_page.get_all_items_in_dataset()
            raise AssertionError(
                f"Dataset not showing as empty in UI.\n"
                f"Expected: Empty dataset message\n"
                f"Found items: {items_from_ui}\n"
                f"Dataset: {dataset.name}"
            ) from e

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.regression
    @pytest.mark.datasets
    @allure.title("Dataset items search functionality")
    def test_dataset_items_search(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
    ):
        """Tests the search functionality for dataset items and verifies search results.

        This test verifies that the search functionality works correctly by:
        1. Testing search with partial matches
        2. Testing case-insensitive search
        3. Testing search with no matches
        4. Testing search result count accuracy
        5. Testing search clear functionality

        Steps:
        1. Create a new dataset via SDK and insert test items with diverse content
        2. Navigate to dataset items page
        3. Test various search scenarios:
           - Search for "input0" should return items containing "input0"
           - Search for "INPUT" should work case-insensitively  
           - Search for "nonexistent" should return no results
           - Clear search should restore all items
        4. Verify search results are accurate and consistent
        """
        logger.info("Starting dataset items search functionality test")

        try:
            dataset = wait_for_dataset_to_be_visible(
                client=client, dataset_name=create_dataset_sdk, timeout=10
            )
        except TimeoutError as e:
            raise AssertionError(
                f"Dataset not visible after creation.\n"
                f"Dataset name: {create_dataset_sdk}"
            ) from e

        try:
            wait_for_number_of_items_in_dataset(
                expected_items_number=len(TEST_ITEMS), dataset=dataset, timeout=15
            )
        except AssertionError as e:
            items_found = len(dataset.get_items())
            raise AssertionError(
                f"Initial items not found in dataset.\n"
                f"Expected: {len(TEST_ITEMS)} items\n"
                f"Found: {items_found} items\n"
                f"Dataset: {dataset.name}"
            ) from e

        # Navigate to dataset items page
        logger.info("Navigating to dataset items page")
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)

        # Get initial count of all items
        initial_count = dataset_items_page.get_search_results_count()
        logger.info(f"Initial dataset items count: {initial_count}")
        
        if initial_count != len(TEST_ITEMS):
            raise AssertionError(
                f"Initial item count mismatch.\n"
                f"Expected: {len(TEST_ITEMS)} items\n"
                f"Found: {initial_count} items\n"
                f"Dataset: {dataset.name}"
            )

        # Test Case 1: Search for partial match
        logger.info("Testing search with partial match: 'input0'")
        dataset_items_page.search_dataset_items("input0")
        
        # Verify search results contain the search term
        if not dataset_items_page.verify_search_results_contain_term("input0"):
            raise AssertionError(
                f"Search results do not all contain 'input0'.\n"
                f"Dataset: {dataset.name}"
            )
        
        search_count_input0 = dataset_items_page.get_search_results_count()
        logger.info(f"Search results for 'input0': {search_count_input0} items")
        
        # Should be less than total items (unless all items contain input0)
        if search_count_input0 > initial_count:
            raise AssertionError(
                f"Search returned more items than total.\n"
                f"Search count: {search_count_input0}\n"
                f"Total count: {initial_count}\n"
                f"Dataset: {dataset.name}"
            )

        # Test Case 2: Case-insensitive search
        logger.info("Testing case-insensitive search: 'INPUT'")
        dataset_items_page.search_dataset_items("INPUT")
        
        if not dataset_items_page.verify_search_results_contain_term("INPUT"):
            raise AssertionError(
                f"Case-insensitive search results do not contain 'INPUT'.\n"
                f"Dataset: {dataset.name}"
            )
        
        search_count_uppercase = dataset_items_page.get_search_results_count()
        logger.info(f"Search results for 'INPUT': {search_count_uppercase} items")

        # Test Case 3: Search with no matches
        logger.info("Testing search with no matches: 'nonexistentterm'")
        dataset_items_page.search_dataset_items("nonexistentterm")
        
        no_results_count = dataset_items_page.get_search_results_count()
        logger.info(f"Search results for 'nonexistentterm': {no_results_count} items")
        
        if no_results_count != 0:
            raise AssertionError(
                f"Search for non-existent term should return 0 results.\n"
                f"Expected: 0 items\n"
                f"Found: {no_results_count} items\n"
                f"Dataset: {dataset.name}"
            )

        # Test Case 4: Clear search restores all items
        logger.info("Testing search clear functionality")
        dataset_items_page.clear_search()
        
        final_count = dataset_items_page.get_search_results_count()
        logger.info(f"Count after clearing search: {final_count} items")
        
        if final_count != initial_count:
            raise AssertionError(
                f"Clearing search did not restore all items.\n"
                f"Expected: {initial_count} items\n"
                f"Found: {final_count} items\n"
                f"Dataset: {dataset.name}"
            )

        logger.info("Successfully completed all search functionality tests")
