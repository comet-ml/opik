from playwright.sync_api import Page, expect
from .BasePage import BasePage


class DatasetsPage(BasePage):
    def __init__(self, page: Page):
        super().__init__(page, "datasets")

    def create_dataset_by_name(self, dataset_name: str):
        self.page.get_by_role("button", name="Create new dataset").first.click()
        self.page.get_by_placeholder("Dataset name").fill(dataset_name)
        self.page.get_by_role("button", name="Create dataset").click()

    def select_database_by_name(self, name):
        self.page.get_by_text(name, exact=True).first.click()

    def search_dataset(self, dataset_name):
        self.page.get_by_test_id("search-input").click()
        self.page.get_by_test_id("search-input").fill(dataset_name)

    def check_dataset_exists_on_page_by_name(self, dataset_name):
        expect(self.page.get_by_text(dataset_name).first).to_be_visible()

    def check_dataset_not_exists_on_page_by_name(self, dataset_name):
        expect(self.page.get_by_text(dataset_name).first).not_to_be_visible()

    def delete_dataset_by_name(self, dataset_name):
        self.search_dataset(dataset_name)
        row = (
            self.page.get_by_role("row")
            .filter(has_text=dataset_name)
            .filter(has=self.page.get_by_role("cell", name=dataset_name, exact=True))
        )
        row.get_by_role("button").click()
        self.page.get_by_role("menuitem", name="Delete").click()
        self.page.get_by_role("button", name="Delete dataset").click()
