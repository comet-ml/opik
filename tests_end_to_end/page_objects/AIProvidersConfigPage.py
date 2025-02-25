from playwright.sync_api import Page, expect
from typing import Optional, Literal
from .BasePage import BasePage


class AIProvidersConfigPage(BasePage):
    def __init__(self, page: Page):
        super().__init__(page, "configuration", "tab=ai-provider")
        self.add_provider_button = self.page.get_by_role(
            "button", name="Add configuration"
        ).first
        self.search_bar = self.page.get_by_test_id("search-input")

    def search_provider_by_name(self, provider_name: str):
        """Search for a provider by name using the search bar"""
        self.search_bar.click()
        self.search_bar.fill(provider_name)
        self.page.wait_for_timeout(500)

    def add_provider(self, provider_type: Literal["OpenAI", "Anthropic"], api_key: str):
        """Add a new AI provider with the specified configuration"""
        self.add_provider_button.click()

        self.page.get_by_role("combobox").click()
        self.page.get_by_role("option", name=provider_type).click()

        self.page.get_by_label("API key").fill(api_key)

        self.page.get_by_role("button", name="Save configuration").click()

    def edit_provider(self, name: str, api_key: Optional[str] = None):
        """Edit an existing AI provider configuration"""
        self.search_provider_by_name(name)

        self.page.get_by_label("API key").fill(api_key)

        self.page.get_by_role("button", name="Update configuration").click()

    def delete_provider(self, provider_name: str):
        """Delete an AI provider by name"""
        self.search_provider_by_name(provider_name)

        self.page.get_by_role("row", name=provider_name).get_by_role(
            "button", name="Actions menu"
        ).click()
        self.page.get_by_role("menuitem", name="Delete").click()

        self.page.get_by_role("button", name="Delete configuration").click()

    def check_provider_exists(self, provider_name: str):
        """Check if a provider exists by name"""
        self.search_provider_by_name(provider_name)
        expect(self.page.get_by_text(provider_name).first).to_be_visible()
        self.search_bar.fill("")

    def check_provider_not_exists(self, provider_name: str):
        """Check if a provider does not exist by name"""
        self.search_provider_by_name(provider_name)
        expect(self.page.get_by_text(provider_name).first).not_to_be_visible()
        self.search_bar.fill("")
