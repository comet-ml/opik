from playwright.sync_api import Page, expect

class ExperimentsPage:
    def __init__(self, page: Page):
        self.page = page
        self.url = '/default/experiments'

    def go_to_page(self):
        self.page.goto(self.url)
    
    def check_experiment_exists_by_name(self, name):
        expect(self.page.get_by_text(name).first).to_be_visible()