from playwright.sync_api import Page, expect

class DatasetsPage:
    def __init__(self, page: Page):
        self.page = page
        self.url = '/default/datasets'

    def go_to_page(self):
        self.page.goto(self.url)

    def select_database_by_name(self, name):
        self.page.get_by_text(name, exact=True).first.click()

    def check_dataset_exists_by_name(self, dataset_name):
        expect(self.page.get_by_text(dataset_name)).to_be_visible()

    