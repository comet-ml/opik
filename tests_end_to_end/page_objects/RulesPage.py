from playwright.sync_api import Page, expect
import logging

logger = logging.getLogger(__name__)


class RulesPage:
    """Page object for managing moderation rules in the online evaluation section"""

    def __init__(self, page: Page):
        self.page = page

    def navigate_to_rules_tab(self):
        """Navigate to the online evaluation rules tab"""
        logger.info("Navigating to rules tab")
        try:
            expect(
                self.page.get_by_role("tab", name="Online evaluation")
            ).to_be_visible()
        except Exception as e:
            raise AssertionError(
                f"Rules tab not found, possible error loading. Error: {str(e)}"
            ) from e

        self.page.get_by_role("tab", name="Online evaluation").click()

    def create_moderation_rule(self, rule_name: str, provider_config, model_config):
        """
        Create a new moderation rule with the specified configuration.

        Args:
            rule_name: The name for the new rule
            provider_config: The provider configuration object
            model_config: The model configuration object

        Returns:
            str: The created rule name
        """
        logger.info(f"Creating new moderation rule: {rule_name}")

        # Click create rule button
        self.page.get_by_role("button", name="Create your first rule").click()

        # Fill rule details
        self.page.get_by_placeholder("Rule name").fill(rule_name)

        # Set sampling rate to 100%
        sampling_value = self.page.locator("#sampling_rate-input")
        sampling_value.fill("1")

        # Select model based on configuration
        self.page.get_by_role("combobox").filter(has_text="Select an LLM model").click()
        self.page.get_by_text(provider_config.display_name, exact=True).hover()

        # Use exact text matching for model selection to avoid conflicts
        try:
            options = self.page.get_by_role("option").all()
            target_option = None

            for option in options:
                text_content = option.inner_text().strip()
                if text_content == model_config.ui_selector:
                    target_option = option
                    break

            if target_option:
                target_option.click()
            else:
                # Fallback to first partial match
                self.page.get_by_role("option").filter(
                    has_text=model_config.ui_selector
                ).first.click()

        except Exception as e:
            logger.warning(
                f"Failed to select model with exact matching, trying fallback: {e}"
            )
            self.page.get_by_role("option").filter(
                has_text=model_config.ui_selector
            ).first.click()

        # Select moderation template
        self.page.get_by_role("combobox").filter(has_text="Custom LLM-as-judge").click()
        self.page.get_by_label("Moderation", exact=True).click()

        # Fill in variable mapping
        variable_map = self.page.get_by_placeholder("Select a key from recent trace")
        variable_map.click()
        variable_map.fill("output.output")
        self.page.get_by_role("option", name="output.output").click()

        # Create rule
        self.page.get_by_role("button", name="Create rule").click()

        logger.info(f"Successfully created moderation rule: {rule_name}")
        return rule_name
