import pytest
from playwright.sync_api import Page
from sdk_helpers import (
    find_project_by_name_sdk,
    delete_project_by_name_sdk,
    wait_for_project_to_be_visible,
    update_project_by_name_sdk,
)
from page_objects.ProjectsPage import ProjectsPage


class TestProjectsCrud:
    @pytest.mark.parametrize("project_fixture", ["create_project", "create_project_ui"])
    @pytest.mark.sanity
    def test_project_visibility(self, request, page: Page, project_fixture):
        """
        Checks a created project is visible via both the UI and SDK. Checks on projects created on both UI and SDK
        1. Create a project via the UI/the SDK (2 "instances" of the test created for each one)
        2. Fetch the project by name using the SDK OpenAI client and check the project exists in the projects table in the UI
        3. Check that the correct project is returned in the SDK and that the name is correct in the UI
        """
        project_name = request.getfixturevalue(project_fixture)

        wait_for_project_to_be_visible(project_name, timeout=10)
        projects_match = find_project_by_name_sdk(project_name)

        assert len(projects_match) > 0
        assert projects_match[0]["name"] == project_name

        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.check_project_exists_on_current_page_with_retry(
            project_name=project_name, timeout=5
        )

    @pytest.mark.parametrize(
        "project_fixture",
        ["create_project", "create_project_ui"],
    )
    def test_project_name_update(self, request, page: Page, project_fixture):
        """
        Checks using the SDK update method on a project. Checks on projects created on both UI and SDK
        1. Create a project via the UI/the SDK (2 "instances" of the test created for each one)
        2. Send a request via the SDK to update the project's name
        3. Check on both the SDK and the UI that the project has been renamed (on SDK: check project ID matches. on UI: check
        project with new name appears and no project with old name appears)
        """

        project_name = request.getfixturevalue(project_fixture)
        new_name = "updated_test_project_name"

        name_updated = False
        try:
            project_id = update_project_by_name_sdk(
                name=project_name, new_name=new_name
            )
            name_updated = True

            wait_for_project_to_be_visible(new_name, timeout=10)
            projects_match = find_project_by_name_sdk(new_name)

            project_id_updated_name = projects_match[0]["id"]
            assert project_id_updated_name == project_id

            projects_page = ProjectsPage(page)
            projects_page.go_to_page()
            projects_page.check_project_exists_on_current_page_with_retry(
                project_name=new_name, timeout=5
            )
            projects_page.check_project_not_exists_on_current_page(
                project_name=project_name
            )

        except Exception as e:
            print(f"Error occurred during update of project name: {e}")
            raise

        finally:
            if name_updated:
                delete_project_by_name_sdk(new_name)
            else:
                delete_project_by_name_sdk(project_name)

    @pytest.mark.parametrize(
        "project_fixture",
        ["create_project", "create_project_ui"],
    )
    def test_project_deletion_in_sdk(self, request, page: Page, project_fixture):
        """
        Checks proper deletion of a project via the SDK. Checks on projects created on both UI and SDK
        1. Create a project via the UI/the SDK (2 "instances" of the test created for each one)
        2. Send a request via the SDK to delete the project
        3. Check on both the SDK and the UI that the project no longer exists (find_projects returns no results in SDK, project does not appear in projects table in UI)
        """
        project_name = request.getfixturevalue(project_fixture)
        delete_project_by_name_sdk(project_name)

        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.check_project_not_exists_on_current_page(
            project_name=project_name
        )

        projects_found = find_project_by_name_sdk(project_name)
        assert len(projects_found) == 0

    @pytest.mark.parametrize(
        "project_fixture",
        ["create_project", "create_project_ui"],
    )
    def test_project_deletion_in_ui(self, request, page: Page, project_fixture):
        """
        Checks proper deletion of a project via the UI. Checks on projects created on both UI and SDK
        1. Create a project via the UI/the SDK (2 "instances" of the test created for each one)
        2. Delete the newly created project via the UI delete button
        3. Check on both the SDK and the UI that the project no longer exists (find_projects returns no results in SDK, project does not appear in projects table in UI)
        """
        project_name = request.getfixturevalue(project_fixture)
        project_page = ProjectsPage(page)
        project_page.go_to_page()
        project_page.delete_project_by_name(project_name)

        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.check_project_not_exists_on_current_page(
            project_name=project_name
        )

        projects_found = find_project_by_name_sdk(project_name)
        assert len(projects_found) == 0
