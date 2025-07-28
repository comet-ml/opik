import pytest
import logging
import allure
from page_objects.PlaygroundPage import PlaygroundPage
from tests.model_config_loader import model_config_loader

# Configure logger for this module
logger = logging.getLogger(__name__)


def pytest_generate_tests(metafunc):
    """Generate test parameters for all enabled playground models"""
    if "model_config" in metafunc.fixturenames:
        models = model_config_loader.get_enabled_models_for_playground()
        if not models:
            pytest.skip("No enabled models found for playground testing")

        # Create test parameters with meaningful IDs
        test_params = []
        test_ids = []

        for provider_name, model_config, provider_config in models:
            test_params.append((provider_name, model_config, provider_config))
            test_ids.append(f"{provider_name}-{model_config.name.replace(' ', '_')}")

        metafunc.parametrize("model_config", test_params, ids=test_ids)


@pytest.mark.regression
@pytest.mark.playground
@pytest.mark.llm_models
@allure.title("Test Opik Playground Basic Functionality")
@allure.description(
    "Verify that the Playground can successfully generate a response to a user prompt for all configured models"
)
def test_playground_basic_functionality(page, model_config, caplog):
    """
    Test the basic functionality of the Opik Playground for multiple models:
    1. Navigate to the Playground page
    2. Configure the specified AI provider
    3. Select the specified model
    4. Enter a prompt
    5. Run the prompt
    6. Verify a response is generated without errors
    """
    provider_name, model_cfg, provider_cfg = model_config
    caplog.set_level(logging.INFO)

    playground_page = PlaygroundPage(page)

    logger.info(
        f"Testing playground with {provider_cfg.display_name} - {model_cfg.name}"
    )

    # Set up AI provider and test playground functionality
    playground_page.setup_ai_provider(provider_name, provider_cfg)
    playground_page.go_to_page()
    playground_page.select_model(provider_cfg.display_name, model_cfg.ui_selector)

    test_prompt = model_config_loader.get_test_prompt()
    playground_page.enter_prompt(test_prompt, message_type="user")
    playground_page.run_prompt()

    # Wait for response or error with better timeout handling
    response_received = playground_page.wait_for_response_or_error(timeout=30)
    assert response_received, "No response received within timeout or error occurred"

    # Validate that no errors are present
    assert (
        not playground_page.has_error()
    ), "Error message was displayed during response generation"

    # Get and validate the response
    response = playground_page.get_response()
    logger.info(f"Response received (excerpt): {response[:100]}...")

    # Perform comprehensive response validation
    validation = playground_page.validate_response_quality(response)

    # Core assertions
    assert validation[
        "has_content"
    ], f"Response is empty or contains only whitespace, full response: '{response}'"
    assert validation[
        "min_length"
    ], f"Response is too short ({validation['response_length']} chars, minimum 25), full response: '{response}'"

    # LLM-specific validation for this prompt
    assert validation[
        "contains_llm_info"
    ], "Response does not contain expected LLM-related information"

    logger.info(
        f"Response validation passed: {validation['response_length']} chars, {validation['sentence_count']} sentences"
    )

    logger.info(
        f"Playground test completed successfully for {provider_cfg.display_name} - {model_cfg.name}"
    )

    # Clean up
    playground_page.cleanup_ai_provider(provider_cfg)
