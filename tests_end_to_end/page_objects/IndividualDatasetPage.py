from playwright.sync_api import Page, expect


class IndividualDatasetPage:
    def __init__(self, page: Page):
        self.page = page
        self.traces_table = page.get_by_role("table")

    def check_cell_exists_by_text(self, text):
        expect(self.traces_table.get_by_text(text, exact=True)).to_be_visible()
