from playwright.sync_api import Page, expect
import time
from .BasePage import BasePage


class ProjectsPage(BasePage):
    def __init__(self, page: Page):
        super().__init__(page, "projects")
        self.projects_table = self.page.get_by_role("table")

    def click_project(self, project_name):
        self.page.get_by_role("link", name=project_name).click()

    def search_project(self, project_name):
        self.page.get_by_test_id("search-input").click()
        self.page.get_by_test_id("search-input").fill(project_name)

    def check_project_exists_on_current_page(self, project_name):
        expect(
            self.page.get_by_role("cell", name=project_name, exact=True)
        ).to_be_visible(timeout=3)

    def check_project_not_exists_on_current_page(self, project_name):
        expect(
            self.page.get_by_role("cell", name=project_name, exact=True)
        ).not_to_be_visible()

    def check_project_exists_on_current_page_with_retry(self, project_name, timeout):
        start_time = time.time()
        while time.time() - start_time < timeout:
            try:
                self.check_project_exists_on_current_page(project_name=project_name)
                break
            except AssertionError:
                self.page.wait_for_timeout(500)
        else:
            raise AssertionError(
                f"project {project_name} not found in projects list within {timeout} seconds"
            )

    def create_new_project(self, project_name):
        self.page.get_by_role("button", name="Create new project").first.click()

        project_name_fill_box = self.page.get_by_placeholder("Project name")
        project_name_fill_box.click()
        project_name_fill_box.fill(project_name)

        self.page.get_by_role("button", name="Create project").click()

    def delete_project_by_name(self, project_name):
        self.search_project(project_name)
        row = (
            self.page.get_by_role("row")
            .filter(has_text=project_name)
            .filter(has=self.page.get_by_role("cell", name=project_name, exact=True))
        )
        row.get_by_role("button").click()
        self.page.get_by_role("menuitem", name="Delete").click()
        self.page.get_by_role("button", name="Delete project").click()
        self.check_project_not_exists_on_current_page(project_name)
