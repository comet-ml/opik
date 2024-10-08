from playwright.sync_api import Page, expect

class TracesPageSpansMenu:
    def __init__(self, page: Page):
        self.page = page

    def check_span_exists_by_name(self, name):
        expect(self.page.get_by_role('button', name=name)).to_be_visible()