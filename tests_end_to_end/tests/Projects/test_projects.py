import pytest
from playwright.sync_api import Page
from sdk_helpers import (
    find_project_by_name_sdk,
    delete_project_by_name_sdk,
    wait_for_project_to_be_visible,
    update_project_by_name_sdk,
)
from page_objects.ProjectsPage import ProjectsPage
import logging
import allure

logger = logging.getLogger(__name__)


class TestProjectsCrud:
    @pytest.mark.parametrize("project_fixture", ["create_project_api", "create_project_ui"])
    @pytest.mark.sanity
    @allure.id("P1")
    @allure.title("Basic project creation - {project_fixture}")
    @allure.description("Test project visibility after creation in both UI and SDK")
    def test_project_visibility(self, request, page: Page, project_fixture):
        """Test project visibility in both UI and SDK interfaces.

        Steps:
        1. Create project via fixture (runs twice: SDK and UI created projects)
        2. Verify via SDK:
           - Project appears in project list
           - Project name matches expected
        3. Verify via UI:
           - Project appears in projects page
           - Project details are correct
        """
        logger.info("Starting project visibility test")
        project_name = request.getfixturevalue(project_fixture)

        # Verify via SDK
        logger.info(f"Verifying project '{project_name}' via SDK")
        try:
            wait_for_project_to_be_visible(project_name, timeout=10)
            projects_match = find_project_by_name_sdk(project_name)

            assert len(projects_match) > 0, (
                f"Project not found via SDK.\n" f"Project name: {project_name}"
            )
            assert projects_match[0]["name"] == project_name, (
                f"Project name mismatch in SDK.\n"
                f"Expected: {project_name}\n"
                f"Got: {projects_match[0]['name']}"
            )
            logger.info("Successfully verified project via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify project via SDK.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to project not created or SDK connectivity issues"
            ) from e

        # Verify via UI
        logger.info("Verifying project in UI")
        projects_page = ProjectsPage(page)
        try:
            projects_page.go_to_page()
            projects_page.check_project_exists_on_current_page_with_retry(
                project_name=project_name, timeout=5
            )
            logger.info("Successfully verified project in UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify project in UI.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to project not visible or page load issues"
            ) from e

    @pytest.mark.parametrize(
        "project_fixture",
        ["create_project_api", "create_project_ui"],
    )
    @allure.id("P2")
    @allure.title("Project name update - {project_fixture}")
    def test_project_name_update(self, request, page: Page, project_fixture):
        """Test project name update via SDK with UI verification.

        Steps:
        1. Create project via fixture (runs twice: SDK and UI created projects)
        2. Update project name via SDK
        3. Verify via SDK:
           - Project found with new name
           - Project ID matches original
        4. Verify via UI:
           - New name appears in project list
           - Old name no longer appears
        5. Clean up by deleting project
        """
        logger.info("Starting project name update test")
        project_name = request.getfixturevalue(project_fixture)
        new_name = "updated_test_project_name"

        name_updated = False
        try:
            # Update name via SDK
            logger.info(f"Updating project name from '{project_name}' to '{new_name}'")
            try:
                project_id = update_project_by_name_sdk(
                    name=project_name, new_name=new_name
                )
                name_updated = True
                logger.info("Successfully updated project name via SDK")
            except Exception as e:
                raise AssertionError(
                    f"Failed to update project name via SDK.\n"
                    f"Original name: {project_name}\n"
                    f"New name: {new_name}\n"
                    f"Error: {str(e)}"
                ) from e

            # Verify via SDK
            logger.info("Verifying project update via SDK")
            try:
                wait_for_project_to_be_visible(new_name, timeout=10)
                projects_match = find_project_by_name_sdk(new_name)
                project_id_updated_name = projects_match[0]["id"]
                assert project_id_updated_name == project_id, (
                    f"Project ID mismatch after update.\n"
                    f"Original ID: {project_id}\n"
                    f"ID after update: {project_id_updated_name}"
                )
                logger.info("Successfully verified project update via SDK")
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify project update via SDK.\n"
                    f"New name: {new_name}\n"
                    f"Error: {str(e)}"
                ) from e

            # Verify via UI
            logger.info("Verifying project update in UI")
            projects_page = ProjectsPage(page)
            try:
                projects_page.go_to_page()
                projects_page.check_project_exists_on_current_page_with_retry(
                    project_name=new_name, timeout=5
                )
                projects_page.check_project_not_exists_on_current_page(
                    project_name=project_name
                )
                logger.info("Successfully verified project update in UI")
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify project update in UI.\n"
                    f"Expected to find: {new_name}\n"
                    f"Expected not to find: {project_name}\n"
                    f"Error: {str(e)}"
                ) from e

        finally:
            # Clean up
            logger.info("Cleaning up test project")
            if name_updated:
                delete_project_by_name_sdk(new_name)
            else:
                delete_project_by_name_sdk(project_name)

    @pytest.mark.parametrize(
        "project_fixture",
        ["create_project_api", "create_project_ui"],
    )
    @allure.id("P3")
    @allure.title("Project deletion in SDK - {project_fixture}")
    def test_project_deletion_in_sdk(self, request, page: Page, project_fixture):
        """Test project deletion via SDK with UI verification.

        Steps:
        1. Create project via fixture (runs twice: SDK and UI created projects)
        2. Delete project via SDK
        3. Verify via UI project no longer appears
        4. Verify via SDK project not found
        """
        logger.info("Starting project deletion via SDK test")
        project_name = request.getfixturevalue(project_fixture)

        # Delete via SDK
        logger.info(f"Deleting project '{project_name}' via SDK")
        try:
            delete_project_by_name_sdk(project_name)
            logger.info("Successfully deleted project via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete project via SDK.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion in UI
        logger.info("Verifying project deletion in UI")
        projects_page = ProjectsPage(page)
        try:
            projects_page.go_to_page()
            projects_page.check_project_not_exists_on_current_page(
                project_name=project_name
            )
            logger.info("Successfully verified project not visible in UI")
        except Exception as e:
            raise AssertionError(
                f"Project still visible in UI after deletion.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion via SDK
        logger.info("Verifying project deletion via SDK")
        try:
            projects_found = find_project_by_name_sdk(project_name)
            assert len(projects_found) == 0, (
                f"Project still exists after deletion.\n"
                f"Project name: {project_name}\n"
                f"Found projects: {projects_found}"
            )
            logger.info("Successfully verified project deletion via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify project deletion via SDK.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.parametrize(
        "project_fixture",
        ["create_project_api", "create_project_ui"],
    )
    @allure.id("P4")
    @allure.title("Project deletion in UI - {project_fixture}")
    def test_project_deletion_in_ui(self, request, page: Page, project_fixture):
        """Test project deletion via UI with SDK verification.

        Steps:
        1. Create project via fixture (runs twice: SDK and UI created projects)
        2. Navigate to projects page
        3. Delete project through UI interface
        4. Verify via UI project no longer appears
        5. Verify via SDK project not found
        """
        logger.info("Starting project deletion via UI test")
        project_name = request.getfixturevalue(project_fixture)

        # Delete via UI
        logger.info(f"Deleting project '{project_name}' via UI")
        project_page = ProjectsPage(page)
        try:
            project_page.go_to_page()
            project_page.delete_project_by_name(project_name)
            logger.info("Successfully deleted project via UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete project via UI.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to delete button not found or dialog issues"
            ) from e

        # Verify deletion in UI
        logger.info("Verifying project deletion in UI")
        try:
            projects_page = ProjectsPage(page)
            projects_page.go_to_page()
            projects_page.check_project_not_exists_on_current_page(
                project_name=project_name
            )
            logger.info("Successfully verified project not visible in UI")
        except Exception as e:
            raise AssertionError(
                f"Project still visible in UI after deletion.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion via SDK
        logger.info("Verifying project deletion via SDK")
        try:
            projects_found = find_project_by_name_sdk(project_name)
            assert len(projects_found) == 0, (
                f"Project still exists after deletion.\n"
                f"Project name: {project_name}\n"
                f"Found projects: {projects_found}"
            )
            logger.info("Successfully verified project deletion via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify project deletion via SDK.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e
