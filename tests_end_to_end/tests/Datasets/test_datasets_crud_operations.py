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
import time


class TestDatasetsCrud:
    def test_create_dataset_ui_add_traces_to_new_dataset(
        self, page: Page, create_project, create_10_test_traces
    ):
        """
        Basic test to check dataset creation via "add to new dataset" functionality in the traces page. Uses the UI after creation to check the project exists
        1. Create a project with some traces
        2. Via the UI, select the traces and add them to a new dataset
        3. Switch to the datasets page, check the dataset exists in the dataset table
        4. If no errors raised and dataset exists, test passes
        """
        dataset_name = "automated_tests_dataset"
        proj_name = create_project
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name=proj_name)

        traces_page = TracesPage(page)
        traces_page.add_all_traces_to_new_dataset(dataset_name=dataset_name)

        try:
            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            datasets_page.check_dataset_exists_on_page_by_name(
                dataset_name=dataset_name
            )
        except Exception as e:
            print(f"error: dataset not created: {e}")
            raise
        finally:
            delete_dataset_by_name_if_exists(dataset_name=dataset_name)

    @pytest.mark.parametrize(
        "dataset_fixture", ["create_dataset_ui", "create_dataset_sdk"]
    )
    @pytest.mark.sanity
    def test_dataset_visibility(
        self, request, page: Page, client: opik.Opik, dataset_fixture
    ):
        """
        Checks a created dataset is visible via both the UI and SDK. Test split in 2: checks on datasets created on both UI and SDK
        1. Create a dataset via the UI/the SDK (2 "instances" of the test created for each one)
        2. Fetch the dataset by name using the SDK Opik client and check the dataset exists in the datasets table in the UI
        3. Check that the correct dataset is returned in the SDK and that the name is correct in the UI
        """
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)

        datasets_page = DatasetsPage(page)
        datasets_page.go_to_page()
        datasets_page.check_dataset_exists_on_page_by_name(dataset_name)

        dataset_sdk = client.get_dataset(dataset_name)
        assert dataset_sdk.name == dataset_name

    @pytest.mark.parametrize(
        "dataset_fixture",
        ["create_dataset_sdk", "create_dataset_ui"],
    )
    def test_dataset_name_update(
        self, request, page: Page, client: opik.Opik, dataset_fixture
    ):
        """
        Checks using the SDK update method on a dataset. Test split into 2: checks on dataset created on both UI and SDK
        1. Create a dataset via the UI/the SDK (2 "instances" of the test created for each one)
        2. Send a request via the SDK OpikApi client to update the dataset's name
        3. Check on both the SDK and the UI that the dataset has been renamed (on SDK: check dataset ID matches when sending a get by name reequest. on UI: check
        dataset with new name appears and no dataset with old name appears)
        """
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)
        new_name = "updated_test_dataset_name"

        name_updated = False
        try:
            dataset_id = update_dataset_name(name=dataset_name, new_name=new_name)
            name_updated = True

            dataset_new_name = get_dataset_by_name(dataset_name=new_name)

            dataset_id_updated_name = dataset_new_name["id"]
            assert dataset_id_updated_name == dataset_id

            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            datasets_page.check_dataset_exists_on_page_by_name(dataset_name=new_name)
            datasets_page.check_dataset_not_exists_on_page_by_name(
                dataset_name=dataset_name
            )

        except Exception as e:
            print(f"Error occured during update of project name: {e}")
            raise

        finally:
            if name_updated:
                delete_dataset_by_name_if_exists(new_name)
            else:
                delete_dataset_by_name_if_exists(dataset_name)

    @pytest.mark.parametrize(
        "dataset_fixture",
        ["create_dataset_sdk", "create_dataset_ui"],
    )
    def test_dataset_deletion_in_sdk(
        self, request, page: Page, client: opik.Opik, dataset_fixture
    ):
        """
        Checks proper deletion of a dataset via the SDK. Test split into 2: checks on datasets created on both UI and SDK
        1. Create a dataset via the UI/the SDK (2 "instances" of the test created for each one)
        2. Send a request via the SDK to delete the dataset
        3. Check on both the SDK and the UI that the dataset no longer exists (client.get_dataset should throw a 404 error, dataset does not appear in datasets table in UI)
        """
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)
        client.delete_dataset(name=dataset_name)
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.check_dataset_not_exists_on_page_by_name(dataset_name=dataset_name)
        try:
            _ = client.get_dataset(dataset_name)
            assert False, f"datasets {dataset_name} somehow still exists after deletion"
        except Exception as e:
            if "404" in str(e) or "not found" in str(e).lower():
                pass
            else:
                raise

    @pytest.mark.parametrize(
        "dataset_fixture",
        ["create_dataset_sdk", "create_dataset_ui"],
    )
    def test_dataset_deletion_in_ui(
        self, request, page: Page, client: opik.Opik, dataset_fixture
    ):
        """
        Checks proper deletion of a dataset via the SDK. Test split into 2: checks on datasets created on both UI and SDK
        1. Create a dataset via the UI/the SDK (2 "instances" of the test created for each one)
        2. Delete the dataset from the UI using the delete button in the datasets page
        3. Check on both the SDK and the UI that the dataset no longer exists (client.get_dataset should throw a 404 error, dataset does not appear in datasets table in UI)
        """
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)
        datasets_page = DatasetsPage(page)
        datasets_page.go_to_page()
        datasets_page.delete_dataset_by_name(dataset_name=dataset_name)
        time.sleep(1)

        try:
            _ = client.get_dataset(dataset_name)
            assert False, f"datasets {dataset_name} somehow still exists after deletion"
        except Exception as e:
            if "404" in str(e) or "not found" in str(e).lower():
                pass
            else:
                raise

        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.check_dataset_not_exists_on_page_by_name(dataset_name=dataset_name)
