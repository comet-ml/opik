from playwright.sync_api import Page, expect

class TracesPage:
    def __init__(self, page: Page):
        self.page = page
        self.traces_table = self.page.get_by_role('table')
        self.trace_names_selector = 'tr td:nth-child(2) div span'

    def get_all_trace_names(self):
        self.page.wait_for_selector(self.trace_names_selector)

        names = self.page.locator(self.trace_names_selector).all_inner_texts()
        return names