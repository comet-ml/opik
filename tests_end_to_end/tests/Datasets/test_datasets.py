import pytest
from playwright.sync_api import Page
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from sdk_helpers import (
    delete_dataset_by_name_if_exists,
    update_dataset_name,
    get_dataset_by_name,
)
import opik
import logging
import allure

logger = logging.getLogger(__name__)


class TestDatasetsCrud:
    @allure.title("Dataset creation via traces page (add traces to new dataset)")
    def test_create_dataset_ui_add_traces_to_new_dataset(
        self, page: Page, create_project_api, create_10_test_traces
    ):
        """Test dataset creation via 'add to new dataset' in traces page.

        Steps:
        1. Create a project with 10 test traces
        2. Navigate to traces page and select all traces
        3. Create new dataset from selected traces
        4. Verify dataset appears in datasets page
        5. Clean up by deleting dataset
        """
        logger.info("Starting dataset creation via traces page test")
        dataset_name = "automated_tests_dataset"
        proj_name = create_project_api

        # Navigate to project and traces
        logger.info(f"Navigating to project {proj_name}")
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name=proj_name)

        # Create dataset from traces
        logger.info(f"Creating dataset {dataset_name} from traces")
        traces_page = TracesPage(page)
        try:
            traces_page.add_all_traces_to_new_dataset(dataset_name=dataset_name)
            logger.info("Successfully created dataset from traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to create dataset from traces.\n"
                f"Dataset name: {dataset_name}\n"
                f"Project: {proj_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify dataset exists
        try:
            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            datasets_page.check_dataset_exists_on_page_by_name(
                dataset_name=dataset_name
            )
            logger.info("Successfully verified dataset exists in UI")
        except Exception as e:
            raise AssertionError(
                f"Dataset not found after creation from traces.\n"
                f"Dataset name: {dataset_name}\n"
                f"Error: {str(e)}"
            ) from e
        finally:
            logger.info(f"Cleaning up - deleting dataset {dataset_name}")
            delete_dataset_by_name_if_exists(dataset_name=dataset_name)

    @pytest.mark.parametrize(
        "dataset_fixture", ["create_dataset_ui", "create_dataset_sdk"]
    )
    @pytest.mark.sanity
    @allure.title("Dataset visibility - {dataset_fixture}")
    def test_dataset_visibility(
        self, request, page: Page, client: opik.Opik, dataset_fixture
    ):
        """Test dataset visibility in both UI and SDK interfaces.

        Steps:
        1. Create dataset via UI or SDK (test runs for both)
        2. Verify dataset appears in UI list
        3. Verify dataset exists and is accessible via SDK
        4. Verify dataset name matches between UI and SDK
        """
        logger.info(
            f"Starting dataset visibility test for dataset created via {dataset_fixture}"
        )
        dataset_name = request.getfixturevalue(dataset_fixture)

        # Verify in UI
        logger.info("Verifying dataset visibility in UI")
        datasets_page = DatasetsPage(page)
        datasets_page.go_to_page()
        try:
            datasets_page.check_dataset_exists_on_page_by_name(dataset_name)
            logger.info("Successfully verified dataset in UI")
        except AssertionError as e:
            raise AssertionError(
                f"Dataset not visible in UI.\n"
                f"Creation method: {dataset_fixture}\n"
                f"Dataset name: {dataset_name}"
            ) from e

        # Verify via SDK
        logger.info("Verifying dataset via SDK")
        try:
            dataset_sdk = client.get_dataset(dataset_name)
            assert dataset_sdk.name == dataset_name, (
                f"Dataset name mismatch.\n"
                f"Expected: {dataset_name}\n"
                f"Got: {dataset_sdk.name}"
            )
            logger.info("Successfully verified dataset via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify dataset via SDK.\n"
                f"Dataset name: {dataset_name}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.parametrize(
        "dataset_fixture",
        ["create_dataset_sdk", "create_dataset_ui"],
    )
    @allure.title("Dataset name update - {dataset_fixture}")
    def test_dataset_name_update(
        self, request, page: Page, client: opik.Opik, dataset_fixture
    ):
        """Test dataset name update via SDK with UI verification.

        Steps:
        1. Create dataset via UI or SDK (test runs for both)
        2. Update dataset name via SDK
        3. Verify via SDK:
           - Get dataset by new name returns same ID
           - Get dataset by old name returns 404
        4. Verify via UI:
           - New name appears in dataset list
           - Old name no longer appears

        The test runs twice:
        - Once for dataset created via SDK
        - Once for dataset created via UI
        """
        logger.info(
            f"Starting dataset name update test for dataset created via {dataset_fixture}"
        )
        dataset_name = request.getfixturevalue(dataset_fixture)
        new_name = "updated_test_dataset_name"
        logger.info(f"Updating dataset name from '{dataset_name}' to '{new_name}'")

        name_updated = False
        try:
            # Update name via SDK
            try:
                dataset_id = update_dataset_name(name=dataset_name, new_name=new_name)
                name_updated = True
                logger.info("Successfully updated dataset name via SDK")
            except Exception as e:
                raise AssertionError(
                    f"Failed to update dataset name via SDK.\n"
                    f"Original name: {dataset_name}\n"
                    f"New name: {new_name}\n"
                    f"Error: {str(e)}"
                ) from e

            # Verify via SDK
            logger.info("Verifying name update via SDK")
            try:
                dataset_new_name = get_dataset_by_name(dataset_name=new_name)
                dataset_id_updated_name = dataset_new_name["id"]
                assert dataset_id_updated_name == dataset_id, (
                    f"Dataset ID mismatch after name update.\n"
                    f"Original ID: {dataset_id}\n"
                    f"ID after update: {dataset_id_updated_name}"
                )
                logger.info("Successfully verified name update via SDK")
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify dataset name update via SDK.\n"
                    f"New name: {new_name}\n"
                    f"Error: {str(e)}"
                ) from e

            # Verify via UI
            logger.info("Verifying name update in UI")
            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            try:
                datasets_page.check_dataset_exists_on_page_by_name(
                    dataset_name=new_name
                )
                datasets_page.check_dataset_not_exists_on_page_by_name(
                    dataset_name=dataset_name
                )
                logger.info("Successfully verified name update in UI")
            except AssertionError as e:
                raise AssertionError(
                    f"Failed to verify dataset name update in UI.\n"
                    f"Expected to find: {new_name}\n"
                    f"Expected not to find: {dataset_name}"
                ) from e

        finally:
            # Clean up
            logger.info("Cleaning up test datasets")
            if name_updated:
                delete_dataset_by_name_if_exists(new_name)
            else:
                delete_dataset_by_name_if_exists(dataset_name)

    @pytest.mark.parametrize(
        "dataset_fixture,deletion_method",
        [
            ("create_dataset_sdk", "sdk"),
            ("create_dataset_sdk", "ui"),
            ("create_dataset_ui", "sdk"),
            ("create_dataset_ui", "ui"),
        ],
    )
    @allure.title(
        "Dataset deletion - {dataset_fixture} and delete via {deletion_method}"
    )
    def test_dataset_deletion(
        self, request, page: Page, client: opik.Opik, dataset_fixture, deletion_method
    ):
        """Test dataset deletion via both SDK and UI interfaces.

        Steps:
        1. Create dataset via UI or SDK
        2. Delete dataset through specified interface (UI or SDK)
        3. Verify deletion via SDK (should get 404)
        4. Verify deletion in UI (should not be visible)

        The test runs four times to test all combinations:
        - Dataset created via SDK, deleted via SDK
        - Dataset created via SDK, deleted via UI
        - Dataset created via UI, deleted via SDK
        - Dataset created via UI, deleted via UI
        """
        logger.info(
            f"Starting dataset deletion test for dataset created via {dataset_fixture}"
            f" and deleted via {deletion_method}"
        )
        dataset_name = request.getfixturevalue(dataset_fixture)

        # Delete dataset via specified method
        logger.info(
            f"Attempting to delete dataset {dataset_name} via {deletion_method}"
        )
        if deletion_method == "sdk":
            try:
                client.delete_dataset(dataset_name)
                logger.info("Successfully deleted dataset via SDK")
            except Exception as e:
                raise AssertionError(
                    f"Failed to delete dataset via SDK.\n"
                    f"Dataset name: {dataset_name}\n"
                    f"Error: {str(e)}"
                ) from e
        else:  # UI deletion
            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            try:
                datasets_page.delete_dataset_by_name(dataset_name=dataset_name)
                logger.info("Successfully deleted dataset via UI")
            except Exception as e:
                raise AssertionError(
                    f"Failed to delete dataset via UI.\n"
                    f"Dataset name: {dataset_name}\n"
                    f"Error: {str(e)}"
                ) from e

        # Verify deletion via SDK
        logger.info("Verifying deletion via SDK")
        try:
            _ = client.get_dataset(dataset_name)
            raise AssertionError(
                f"Dataset still exists after deletion.\n"
                f"Dataset name: {dataset_name}\n"
                f"Deletion method: {deletion_method}\n"
                f"Expected: 404 error\n"
                f"Got: Dataset still accessible"
            )
        except Exception as e:
            if "404" in str(e) or "not found" in str(e).lower():
                logger.info("Successfully verified deletion via SDK (404 error)")
            else:
                raise AssertionError(
                    f"Unexpected error checking dataset deletion.\n"
                    f"Dataset name: {dataset_name}\n"
                    f"Deletion method: {deletion_method}\n"
                    f"Expected: 404 error\n"
                    f"Got: {str(e)}"
                ) from e

        # Verify deletion in UI
        logger.info("Verifying deletion in UI")
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        try:
            dataset_page.check_dataset_not_exists_on_page_by_name(
                dataset_name=dataset_name
            )
            logger.info("Successfully verified dataset not visible in UI")
        except AssertionError as e:
            raise AssertionError(
                f"Dataset still visible in UI after deletion.\n"
                f"Dataset name: {dataset_name}\n"
                f"Deletion method: {deletion_method}"
            ) from e
