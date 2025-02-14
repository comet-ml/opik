from playwright.sync_api import Page, expect, Locator
import re


class PromptPage:
    def __init__(self, page: Page):
        self.page = page
        self.next_page_button_locator = (
            self.page.locator("div")
            .filter(has_text=re.compile(r"^Showing (\d+)-(\d+) of (\d+)"))
            .nth(2)
            .locator("button:nth-of-type(3)")
        )

    def edit_prompt(self, new_prompt: str):
        self.page.get_by_role("button", name="Edit prompt").click()
        self.page.get_by_role("textbox", name="Prompt").click()
        self.page.get_by_role("textbox", name="Prompt").fill(new_prompt)
        self.page.get_by_role("button", name="Create new commit").click()

    def click_most_recent_commit(self):
        self.page.get_by_role("tab", name="Commits").click()
        expect(self.page.get_by_role("row").nth(1)).to_be_visible()
        self.page.get_by_role("row").nth(1).get_by_role("link").click()
        self.page.wait_for_timeout(500)

    def get_prompt_of_selected_commit(self):
        return self.page.get_by_role("code").first.inner_text()

    def get_all_prompt_versions_with_commit_ids_on_page(self):
        rows: list[Locator] = self.page.get_by_role("row").all()[1:]
        versions = {}
        for row in rows:
            prompt = row.get_by_role("cell").nth(2).inner_text()
            commit_id = row.get_by_role("link").first.inner_text()
            versions[prompt] = commit_id

        return versions

    def get_all_prompt_versions_with_commit_ids_for_prompt(self):
        self.page.get_by_role("tab", name="Commits").click()
        expect(self.page.get_by_role("table")).to_be_visible()
        versions = {}
        first_page = self.get_all_prompt_versions_with_commit_ids_on_page()
        versions.update(first_page)
        while (
            self.next_page_button_locator.is_visible()
            and self.next_page_button_locator.is_enabled()
        ):
            self.next_page_button_locator.click()
            self.page.wait_for_timeout(500)
            versions.extend(self.get_all_prompt_versions_with_commit_ids_on_page())

        return versions
