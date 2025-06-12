from playwright.sync_api import Page, expect
from page_objects.BasePage import BasePage
import logging
import sys

# Configure logger for this module
logger = logging.getLogger(__name__)

# Add console handler to ensure logs appear in console
console_handler = logging.StreamHandler(sys.stdout)
console_handler.setLevel(logging.INFO)
formatter = logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
console_handler.setFormatter(formatter)
logger.addHandler(console_handler)
logger.setLevel(logging.INFO)


class PlaygroundPage(BasePage):
    def __init__(self, page: Page):
        """
        Initialize the PlaygroundPage object.

        Args:
            page: Playwright page object
        """
        super().__init__(page, "playground")
        self.page = page

        self.model_selector = self.page.get_by_role("combobox").first
        self.prompt_input = self.page.get_by_role("textbox").first
        self.run_button = self.page.get_by_role("button", name="Run")
        self.output_area = self.page.locator('p:text("Output A") ~ div').first
        self.error_message = self.page.locator(
            "text=Please select an LLM model for your prompt"
        )

    def verify_model_selected(self, expected_model_contains=None):
        """
        Verify that a model is selected. Optionally check if it contains expected text.

        Args:
            expected_model_contains: Optional text that the selected model should contain
        """
        expect(self.model_selector).to_be_visible()

        if expected_model_contains:
            model_text = self.model_selector.inner_text()
            print(f"Current model selection: {model_text}")
            assert (
                expected_model_contains in model_text
            ), f"Expected model to contain '{expected_model_contains}', but got '{model_text}'"
            logger.info(f"Model verified to contain '{expected_model_contains}'")

    def enter_prompt(self, prompt_text: str):
        """
        Enter a prompt in the prompt input area.

        Args:
            prompt_text: The prompt text to enter
        """
        logger.info(f"Entering prompt: {prompt_text}")
        self.prompt_input.click()
        self.prompt_input.fill(prompt_text)

    def run_prompt(self):
        """
        Click the Run button and wait for the response.
        """
        logger.info("Clicking Run button")
        expect(self.run_button).to_be_enabled(timeout=5000)
        self.run_button.click()

        # Wait for the response to be generated
        logger.info("Waiting for response...")
        self.page.wait_for_load_state("networkidle")
        self.page.wait_for_timeout(2000)

    def get_response(self):
        """
        Get the response text from the output area.

        Returns:
            str: The response text
        """
        logger.info("Getting response text")

        # Wait for output area to be visible
        expect(self.output_area).to_be_visible(timeout=5000)

        response_text = self.output_area.inner_text()

        # Log excerpt of response
        response_excerpt = (
            response_text[:100] + "..." if len(response_text) > 100 else response_text
        )
        logger.info(f"Response received: {response_excerpt}")

        return response_text

    def has_error(self):
        """
        Check if there is an error message.

        Returns:
            bool: True if there is an error message, False otherwise
        """
        has_error = self.error_message.is_visible()
        if has_error:
            error_text = self.error_message.inner_text()
            logger.warning(f"Error message detected: {error_text}")
        return has_error
