from playwright.sync_api import Page, expect

class ProjectsPage:
    def __init__(self, page: Page):
        self.page = page
        self.url = '/projects'
        self.projects_table = page.get_by_role('table')

    def go_to_page(self):
        self.page.goto(self.url)

    def click_project(self, project_name):
        self.page.get_by_role('cell', name=project_name).click()

    def check_project_exists(self, project_name):
        expect(self.projects_table.get_by_role('cell', name=project_name)).to_be_visible()