from playwright.sync_api import Page, expect
from page_objects.BasePage import BasePage
import logging
import re

# Configure logger for this module
logger = logging.getLogger(__name__)


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
        self.output_response = self.page.locator('p:text("Output A")').locator(
            "xpath=following-sibling::div[1]"
        )
        self.error_message = self.page.locator(
            "text=Please select an LLM model for your prompt"
        )
        # Look for various error patterns that might appear
        self.api_error_messages = [
            "messages: at least one message is required",
            "API key",
            "error",
            "failed",
            "Invalid",
            "unauthorized",
        ]

    def select_model(self, provider_name: str, model_name: str):
        """
        Select a specific model from a provider.

        Args:
            provider_name: The name of the provider (e.g., "OpenAI", "Anthropic")
            model_name: The name of the model to select
        """
        logger.info(f"Selecting model: {provider_name} -> {model_name}")

        # Click the model selector to open the dropdown
        self.model_selector.click()

        # First hover over the provider to expand it
        provider_element = self.page.get_by_text(provider_name, exact=True)
        provider_element.hover()

        # Then click on the specific model option - find option with exact text content
        # First try to find exact match, then fallback to first match if needed
        try:
            # Look for option that contains exactly the model name as text content
            options = self.page.get_by_role("option").all()
            target_option = None

            for option in options:
                text_content = option.inner_text().strip()
                if text_content == model_name:
                    target_option = option
                    break

            if target_option:
                target_option.click()
            else:
                # Fallback to first partial match
                self.page.get_by_role("option").filter(
                    has_text=model_name
                ).first.click()

        except Exception as e:
            logger.warning(
                f"Failed to select model with exact matching, trying fallback: {e}"
            )
            self.page.get_by_role("option").filter(has_text=model_name).first.click()

        logger.info(f"Successfully selected {provider_name} -> {model_name}")

    def verify_model_available(self, provider_name: str, model_name: str):
        """
        Verify that a specific model is available in the selector.

        Args:
            provider_name: The name of the provider
            model_name: The name of the model to check
        """
        logger.info(f"Verifying model availability: {provider_name} -> {model_name}")

        # Click the model selector to open the dropdown
        self.model_selector.click()

        # Check if provider is visible
        provider_element = self.page.get_by_text(provider_name, exact=True)
        expect(provider_element).to_be_visible()

        # Hover over provider to see models
        provider_element.hover()

        # Check if model is visible
        model_element = self.page.get_by_role("option").filter(has_text=model_name)
        expect(model_element).to_be_visible()

        # Close the dropdown by clicking elsewhere
        self.page.keyboard.press("Escape")

        logger.info(f"Model {provider_name} -> {model_name} is available")

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

    def enter_prompt(self, prompt_text: str, message_type: str = "user"):
        """
        Enter a prompt in the prompt input area.

        Args:
            prompt_text: The prompt text to enter
            message_type: Type of message - "user", "system", or "assistant"
        """
        logger.info(f"Entering {message_type} prompt: {prompt_text}")

        # First, use the existing (first) message box and set it to the desired type
        # This avoids empty message boxes that disable the Run button

        # Check if there's already a message type button (System, User, Assistant)
        existing_buttons = self.page.get_by_role("button").filter(
            has_text=re.compile(r"^(System|User|Assistant)$")
        )

        if existing_buttons.count() > 0:
            # Use the first existing message box
            first_button = existing_buttons.first
            current_type = first_button.inner_text().strip()

            if current_type.lower() != message_type.lower():
                # Change the message type using the dropdown
                logger.info(
                    f"Changing message type from {current_type} to {message_type}"
                )
                first_button.click()
                self.page.get_by_role(
                    "menuitemcheckbox", name=message_type.capitalize()
                ).click()

            # Fill the first textbox with our prompt
            first_textbox = self.page.get_by_role("textbox").first
            first_textbox.click()
            first_textbox.fill(prompt_text)
            logger.info(
                f"Filled first message box ({message_type}) with: {prompt_text[:50]}..."
            )

        else:
            # Fallback: add a new message if no existing ones found
            logger.info("No existing message boxes found, adding new user message")
            message_button = self.page.get_by_role("button", name="Message", exact=True)
            if message_button.is_visible():
                message_button.click()
                self.page.wait_for_timeout(500)

            # Fill the newly created textbox
            user_textboxes = self.page.get_by_role("textbox").all()
            if user_textboxes:
                target_textbox = user_textboxes[-1]
                target_textbox.click()
                target_textbox.fill(prompt_text)
                logger.info(f"Filled new user message with: {prompt_text[:50]}...")
            else:
                raise Exception("Could not find any textbox to fill")

    def run_prompt(self):
        """
        Click the Run button and wait for the response.
        """
        logger.info("Waiting for Run button to be enabled...")

        # Wait a bit longer for the button to become enabled after entering text
        try:
            expect(self.run_button).to_be_enabled(timeout=10000)
            logger.info("Run button is enabled, clicking...")
            self.run_button.click()
        except Exception as e:
            # Debug info if button is not enabled
            button_text = self.run_button.inner_text()
            is_disabled = self.run_button.is_disabled()
            logger.error(
                f"Run button not enabled. Text: '{button_text}', Disabled: {is_disabled}"
            )

            # Check if we have proper messages
            textboxes = self.page.get_by_role("textbox").all()
            logger.error(f"Found {len(textboxes)} textboxes")
            for i, textbox in enumerate(textboxes):
                content = textbox.input_value()
                logger.error(f"Textbox {i}: '{content[:50]}...'")

            raise Exception(f"Run button not enabled after 10 seconds: {e}")

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
        # Check for the specific "Please select" error message
        has_select_error = self.error_message.is_visible()
        if has_select_error:
            error_text = self.error_message.inner_text()
            logger.warning(f"Model selection error detected: {error_text}")
            return True

        # Check for API errors in the output area
        for error_pattern in self.api_error_messages:
            if self.page.get_by_text(error_pattern).is_visible():
                logger.warning(f"API error detected: {error_pattern}")
                return True

        return False

    def wait_for_response_or_error(self, timeout=30):
        """
        Wait for either a response to appear or an error to be displayed.

        Args:
            timeout: Maximum time to wait in seconds

        Returns:
            bool: True if response appears, False if error occurs
        """
        logger.info("Waiting for response or error...")

        for _ in range(timeout):
            # Check if we have a response
            if self.output_response.is_visible():
                response_text = self.output_response.inner_text().strip()
                if response_text and len(response_text) > 10:  # Meaningful response
                    logger.info("Response received successfully")
                    return True

            # Check for errors
            if self.has_error():
                logger.warning("Error detected while waiting for response")
                return False

            # Wait a bit before checking again
            self.page.wait_for_timeout(1000)

        logger.warning(f"Timeout after {timeout} seconds waiting for response")
        return False

    def validate_response_quality(self, response_text):
        """
        Validate the quality and completeness of the response.

        Args:
            response_text: The response text to validate

        Returns:
            dict: Validation results with various checks
        """
        validation = {
            "has_content": bool(response_text and response_text.strip()),
            "min_length": len(response_text) >= 25,
            "coherent_sentences": "." in response_text
            and len(response_text.split(".")) >= 2,
            "no_truncation": not response_text.endswith("..."),
            "contains_llm_info": any(
                term in response_text.lower()
                for term in [
                    "language model",
                    "llm",
                    "artificial intelligence",
                    "ai",
                    "neural",
                ]
            ),
            "response_length": len(response_text),
            "sentence_count": len([s for s in response_text.split(".") if s.strip()]),
        }

        return validation

    def setup_ai_provider(self, provider_name: str, provider_config):
        """
        Set up AI provider configuration for testing.

        Args:
            provider_name: The provider name (e.g., "openai", "anthropic")
            provider_config: The provider configuration object
        """
        from page_objects.helpers.AIProviderSetupHelper import AIProviderSetupHelper

        helper = AIProviderSetupHelper(self.page)
        helper.setup_provider_if_needed(provider_name, provider_config)

    def cleanup_ai_provider(self, provider_config):
        """
        Clean up AI provider configuration after testing.

        Args:
            provider_config: The provider configuration object
        """
        from page_objects.helpers.AIProviderSetupHelper import AIProviderSetupHelper

        helper = AIProviderSetupHelper(self.page)
        helper.cleanup_provider(provider_config)
