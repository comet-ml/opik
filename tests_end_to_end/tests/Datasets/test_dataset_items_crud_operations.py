import pytest
from playwright.sync_api import Page, expect
from page_objects.DatasetsPage import DatasetsPage
from page_objects.DatasetItemsPage import DatasetItemsPage
from Datasets.datasets_utils import (
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


class TestDatasetItemsCrud:
    @pytest.mark.parametrize(
        "dataset_insert", ["insert_via_sdk"]
    )  # add insert_via_ui once flakiness is figured out
    @pytest.mark.parametrize(
        "dataset_creation_fixture",
        ["create_dataset_sdk", "create_dataset_ui"],
    )
    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.sanity
    def test_dataset_item_insertion(
        self,
        request,
        page: Page,
        client: opik.Opik,
        dataset_creation_fixture,
        dataset_insert,
    ):
        """
        Tests insertion into database in all possible ways of creating dataset and the items themselves (creates 4 test instances), and checks syncing between UI and SDK
        1. Create a dataset via either the SDK or the UI
        2. Insert 10 items into uit via the SDK or the UI
        3. Check that the items are visible in both the SDK and the UI
        All 4 possible combinations are tested:
            - dataset created by SDK - items inserted by UI - check they all appear correctly in both UI and SDK
            - dataset created by SDK - items inserted by SDK - check they all appear correctly in both UI and SDK
            - dataset created by UI - items inserted by UI - check they all appear correctly in both UI and SDK
            - dataset created by UI - items inserted by SDK - check they all appear correctly in both UI and SDK
        """

        dataset = wait_for_dataset_to_be_visible(
            client=client,
            dataset_name=request.getfixturevalue(dataset_creation_fixture),
            timeout=10,
        )

        if "ui" in dataset_insert:
            insert_dataset_items_ui(page, dataset.name, TEST_ITEMS)
        elif "sdk" in dataset_insert:
            insert_dataset_items_sdk(client, dataset.name, TEST_ITEMS)

        wait_for_number_of_items_in_dataset(
            expected_items_number=len(TEST_ITEMS), dataset=dataset, timeout=10
        )

        items_from_sdk = dataset.get_items()

        # CHECK THAT THE ITEMS INSERTED ARE EXACTLY THE ITEMS RETURNED BY THE SDK
        assert compare_item_lists(expected=TEST_ITEMS, actual=items_from_sdk)

        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)
        items_from_ui = dataset_items_page.get_all_items_in_dataset()

        # CHECK THAT THE ITEMS INSERTED ARE EXACTLY THE ITEMS FOUND IN THE UI
        assert compare_item_lists(expected=TEST_ITEMS, actual=items_from_ui)

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    def test_dataset_item_update(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
    ):
        """
        Tests updating existing dataset items with new information and the change syncing to both the UI and the SDK
        1. Create a dataset via the SDK
        2. Insert 10 items into it via the SDK
        3. Using dataset.update(), insert new data into the existing dataset items
        4. Check that the new data is correct on both the UI and the SDK
        """
        dataset = wait_for_dataset_to_be_visible(
            client=client, dataset_name=create_dataset_sdk, timeout=10
        )

        wait_for_number_of_items_in_dataset(
            expected_items_number=len(TEST_ITEMS), dataset=dataset, timeout=15
        )

        items_from_sdk = dataset.get_items()
        updated_items = get_updated_items(
            current=items_from_sdk, update=TEST_ITEMS_UPDATE
        )
        dataset.update(updated_items)

        items_from_sdk = dataset.get_items()

        # CHECK THAT THE ITEMS FROM THE SDK EXACTLY MATCH THE UPDATED DATASET ITEMS DATA
        assert compare_item_lists(expected=TEST_ITEMS_UPDATE, actual=items_from_sdk)

        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)
        items_from_ui = dataset_items_page.get_all_items_in_dataset()

        # CHECK THAT THE ITEMS FOUND IN THE UI EXACTLY MATCH THE UPDATED DATASET ITEMS DATA
        assert compare_item_lists(expected=TEST_ITEMS_UPDATE, actual=items_from_ui)

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    @pytest.mark.parametrize("item_deletion", ["delete_via_ui", "delete_via_sdk"])
    def test_dataset_item_deletion(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
        item_deletion,
    ):
        """
        Tests deletion of an item via both the UI and the SDK (2 test instances created) and the change being visible in both the UI and the SDK
        1. Create a dataset via the SDK
        2. Insert 10 items into it via the SDK
        3. Using either the UI or the SDK (2 tests), delete one item from the dataset
        4. Check that the item with that data no longer exists in both the SDK and the UI and that the length of the item list is updated
        """
        dataset = wait_for_dataset_to_be_visible(
            client=client, dataset_name=create_dataset_sdk, timeout=10
        )

        item_deleted = {}
        if "ui" in item_deletion:
            item_deleted = delete_one_dataset_item_ui(page, dataset.name)
        elif "sdk" in item_deletion:
            item_deleted = delete_one_dataset_item_sdk(client, dataset.name)

        wait_for_number_of_items_in_dataset(
            expected_items_number=len(TEST_ITEMS) - 1, dataset=dataset, timeout=15
        )

        items_from_sdk = dataset.get_items()

        deleted_item_not_in_sdk_list = not any(
            item["input"] == item_deleted["input"]
            and item["output"] == item_deleted["output"]
            for item in items_from_sdk
        )

        # CHECK DATA OF DELETED ITEM NO LONGER PRESENT IN DATASET WHEN GETTING ITEMS FROM SDK
        assert deleted_item_not_in_sdk_list

        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)
        items_from_ui = dataset_items_page.get_all_items_in_dataset()

        assert len(items_from_ui) == len(TEST_ITEMS) - 1

        deleted_item_not_in_ui_list = not any(
            item["input"] == item_deleted["input"]
            and item["output"] == item_deleted["output"]
            for item in items_from_ui
        )

        # CHECK DATA OF DELETED ITEM NO LONGER PRESENT IN DATASET WHEN GETTING ITEMS FROM UI
        assert deleted_item_not_in_ui_list

    @pytest.mark.browser_context_args(permissions=["clipboard-read"])
    def test_dataset_clear(
        self,
        request,
        page: Page,
        client: opik.Opik,
        create_dataset_sdk,
        insert_dataset_items_sdk,
    ):
        """
        Tests mass deletion from the dataset using dataset.clear()
        1. Create a dataset via the SDK
        2. Insert 10 items into it via the SDK
        3. Deleting every item from the dataset using dataset.clear()
        4. Check that no items exist in the dataset when trying to get them via both the SDK and the UI
        """
        dataset = wait_for_dataset_to_be_visible(
            client=client, dataset_name=create_dataset_sdk, timeout=10
        )
        dataset.clear()

        # CHECK NO ITEMS RETURNED FROM THE SDK
        wait_for_number_of_items_in_dataset(
            expected_items_number=0, dataset=dataset, timeout=15
        )

        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.select_database_by_name(dataset.name)
        dataset_items_page = DatasetItemsPage(page)

        # CHECK -DATASET EMPTY- MESSAGE APPEARS, SIGNIFYING AN EMPTY DATASET
        expect(
            dataset_items_page.page.get_by_text("There are no dataset items yet")
        ).to_be_visible()
