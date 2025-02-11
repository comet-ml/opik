from playwright.sync_api import Page, expect
import time
from .BasePage import BasePage


class PromptLibraryPage(BasePage):
    def __init__(self, page: Page):
        super().__init__(page, "prompts")
        self.prompts_table = self.page.get_by_role("table")

    def click_prompt(self, prompt_name):
        self.page.get_by_role("link", name=prompt_name).click()

    def search_prompt(self, prompt_name):
        self.page.get_by_test_id("search-input").click()
        self.page.get_by_test_id("search-input").fill(prompt_name)

    def check_prompt_exists_on_current_page(self, prompt_name):
        expect(
            self.page.get_by_role("cell", name=prompt_name, exact=True)
        ).to_be_visible()

    def check_prompt_exists_in_workspace(self, prompt_name):
        self.search_prompt(prompt_name=prompt_name)
        self.check_prompt_exists_on_current_page(prompt_name=prompt_name)

    def check_prompt_not_exists_on_current_page(self, prompt_name):
        expect(
            self.page.get_by_role("cell", name=prompt_name, exact=True)
        ).not_to_be_visible(timeout=1000)

    def check_prompt_not_exists_in_workspace(self, prompt_name):
        self.search_prompt(prompt_name=prompt_name)
        self.check_prompt_not_exists_on_current_page(prompt_name=prompt_name)

    def check_prompt_exists_on_current_page_with_retry(self, prompt_name, timeout):
        start_time = time.time()
        while time.time() - start_time < timeout:
            try:
                self.check_prompt_exists_on_current_page(prompt_name=prompt_name)
                break
            except AssertionError:
                self.page.wait_for_timeout(500)
        else:
            raise AssertionError(
                f"prompt {prompt_name} not found in prompts list within {timeout} seconds"
            )

    def create_new_prompt(self, name, prompt):
        self.page.get_by_role("button", name="Create new prompt").first.click()
        self.page.get_by_placeholder("Prompt name").fill(name)
        self.page.get_by_placeholder("Prompt", exact=True).click()
        self.page.get_by_placeholder("Prompt", exact=True).fill(prompt)
        self.page.get_by_role("button", name="Create prompt").click()

    def delete_prompt_by_name(self, prompt_name):
        self.search_prompt(prompt_name)
        row = (
            self.page.get_by_role("row")
            .filter(has_text=prompt_name)
            .filter(has=self.page.get_by_role("cell", name=prompt_name, exact=True))
        )
        row.get_by_role("button").click()
        self.page.get_by_role("menuitem", name="Delete").click()
        self.page.get_by_role("button", name="Delete prompt").click()
