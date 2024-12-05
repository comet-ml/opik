from playwright.sync_api import Page, expect

class ExperimentsPage:
    def __init__(self, page: Page):
        self.page = page
        self.url = '/default/experiments'
        self.search_bar = self.page.get_by_test_id('search-input')

    def go_to_page(self):
        self.page.goto(self.url)

    def search_experiment_by_name(self, exp_name: str):
        self.search_bar.click()
        self.search_bar.fill(exp_name)
    
    def check_experiment_exists_by_name(self, exp_name: str):
        self.search_experiment_by_name(exp_name)
        expect(self.page.get_by_text(exp_name).first).to_be_visible()
    
    def check_experiment_not_exists_by_name(self, exp_name: str):
        self.search_experiment_by_name(exp_name)
        expect(self.page.get_by_text(exp_name)).not_to_be_visible()

    def delete_experiment_by_name(self, exp_name: str):
        self.search_experiment_by_name(exp_name)
        self.page.get_by_role('row', name=exp_name).first.get_by_role('button', name='Actions menu').click()
        self.page.get_by_role('menuitem', name='Delete').click()
        self.page.get_by_role('button', name='Delete experiment').click()