import pytest
from playwright.sync_api import Page, expect
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from sdk_helpers import delete_dataset_by_name_if_exists, update_dataset_name, get_dataset_by_name
import opik
import time


class TestDatasetsCrud:

    def test_create_dataset_ui_datasets_page(self, page: Page):
        datasets_page = DatasetsPage(page)
        datasets_page.go_to_page()
        dataset_name = 'automated_tests_dataset'
        try:
            datasets_page.create_dataset_by_name(dataset_name=dataset_name)
            datasets_page.check_dataset_exists_on_page_by_name(dataset_name=dataset_name)
        except Exception as e:
            print(f'error during dataset creation: {e}')
            raise
        finally:
            delete_dataset_by_name_if_exists(dataset_name=dataset_name)


    def test_create_dataset_ui_add_traces_to_new_dataset(self, page: Page, create_delete_project_sdk, create_10_test_traces):
        dataset_name = 'automated_tests_dataset'
        proj_name = create_delete_project_sdk
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name=proj_name)

        traces_page = TracesPage(page)
        traces_page.add_all_traces_to_new_dataset(dataset_name=dataset_name)
        
        try:
            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            datasets_page.check_dataset_exists_on_page_by_name(dataset_name=dataset_name)
        except Exception as e:
            print(f'error: dataset not created: {e}')
            raise
        finally:
            delete_dataset_by_name_if_exists(dataset_name=dataset_name)


    def test_create_dataset_sdk_client(self, client: opik.Opik):
        dataset_name = 'automated_tests_dataset'
        try:
            client.create_dataset(name=dataset_name)
            time.sleep(0.2)
            assert client.get_dataset(name=dataset_name) is not None
        except Exception as e:
            print(f'error during dataset creation: {e}')
            raise
        finally:
            delete_dataset_by_name_if_exists(dataset_name=dataset_name)
            

    @pytest.mark.parametrize('dataset_fixture', ['create_delete_dataset_ui', 'create_delete_dataset_sdk'])
    def test_dataset_visibility(self, request, page: Page, client: opik.Opik, dataset_fixture):
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)

        datasets_page = DatasetsPage(page)
        datasets_page.go_to_page()
        datasets_page.check_dataset_exists_on_page_by_name(dataset_name)

        assert client.get_dataset(dataset_name) is not None

    
    @pytest.mark.parametrize('dataset_fixture', ['create_dataset_sdk_no_cleanup', 'create_dataset_ui_no_cleanup'])
    def test_dataset_name_update(self, request, page: Page, client: opik.Opik, dataset_fixture):
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)
        new_name = 'updated_test_dataset_name'

        name_updated = False
        try:
            dataset_id = update_dataset_name(name=dataset_name, new_name=new_name)
            name_updated = True

            dataset_new_name = get_dataset_by_name(dataset_name=new_name)

            dataset_id_updated_name = dataset_new_name['id']
            assert dataset_id_updated_name == dataset_id

            datasets_page = DatasetsPage(page)
            datasets_page.go_to_page()
            datasets_page.check_dataset_exists_on_page_by_name(dataset_name=new_name)
            datasets_page.check_dataset_not_exists_on_page_by_name(dataset_name=dataset_name)

        except Exception as e:
            print(f'Error occured during update of project name: {e}')
            raise

        finally:
            if name_updated:
                delete_dataset_by_name_if_exists(new_name)
            else:
                delete_dataset_by_name_if_exists(dataset_name)


    @pytest.mark.parametrize('dataset_fixture', ['create_dataset_sdk_no_cleanup', 'create_dataset_ui_no_cleanup'])
    def test_dataset_deletion_in_sdk(self, request, page: Page, client: opik.Opik, dataset_fixture):
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)
        client.delete_dataset(name=dataset_name)
        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.check_dataset_not_exists_on_page_by_name(dataset_name=dataset_name)
        try:
            _ = client.get_dataset(dataset_name)
            assert False, f'datasets {dataset_name} somehow still exists after deletion'
        except Exception as e:
            if '404' in str(e) or 'not found' in str(e).lower():
                pass
            else:
                raise


    @pytest.mark.parametrize('dataset_fixture', ['create_dataset_sdk_no_cleanup', 'create_dataset_ui_no_cleanup'])
    def test_dataset_deletion_in_ui(self, request, page: Page, client: opik.Opik, dataset_fixture):
        dataset_name = request.getfixturevalue(dataset_fixture)
        time.sleep(0.5)
        datasets_page = DatasetsPage(page)
        datasets_page.go_to_page()
        datasets_page.delete_dataset_by_name(dataset_name=dataset_name)
        time.sleep(1)

        try:
            _ = client.get_dataset(dataset_name)
            assert False, f'datasets {dataset_name} somehow still exists after deletion'
        except Exception as e:
            if '404' in str(e) or 'not found' in str(e).lower():
                pass
            else:
                raise

        dataset_page = DatasetsPage(page)
        dataset_page.go_to_page()
        dataset_page.check_dataset_not_exists_on_page_by_name(dataset_name=dataset_name)

