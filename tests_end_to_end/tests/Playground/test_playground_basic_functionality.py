import pytest
import logging
import allure
from page_objects.PlaygroundPage import PlaygroundPage

# Configure logger for this module
logger = logging.getLogger(__name__)

# Add console handler to ensure logs appear in console
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
formatter = logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
console_handler.setFormatter(formatter)
logger.addHandler(console_handler)
logger.setLevel(logging.INFO)


@pytest.mark.regression
@pytest.mark.playground
@allure.title("Test Opik Playground Basic Functionality")
@allure.description(
    "Verify that the Playground can successfully generate a response to a user prompt"
)
def test_playground_basic_functionality(page, create_ai_provider_config, caplog):
    """
    Test the basic functionality of the Opik Playground:
    1. Navigate to the Playground page
    2. Verify model is already selected (OpenAI)
    3. Enter a prompt
    4. Run the prompt
    5. Verify a response is generated without errors
    """
    caplog.set_level(logging.INFO)

    playground_page = PlaygroundPage(page)

    logger.info("Navigating to the Playground page")
    playground_page.go_to_page()

    logger.info("Verifying model is selected")
    playground_page.verify_model_selected("OpenAI")

    test_prompt = "Explain what is an LLM in one paragraph."
    logger.info(f"Entering prompt: {test_prompt}")
    playground_page.enter_prompt(test_prompt)

    logger.info("Running the prompt")
    playground_page.run_prompt()

    response = playground_page.get_response()
    logger.info(f"Response received (excerpt): {response[:100]}...")

    assert response, "No response was generated"
    assert not playground_page.has_error(), "Error message was displayed"
    assert len(response) > 50, "Response is too short to be valid"

    logger.info("Playground test completed successfully")
