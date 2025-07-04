import pytest
import logging
import allure
from page_objects.PlaygroundPage import PlaygroundPage
from tests.model_config_loader import model_config_loader

# Configure logger for this module
logger = logging.getLogger(__name__)

# Add console handler to ensure logs appear in console
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
formatter = logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
console_handler.setFormatter(formatter)
logger.addHandler(console_handler)
logger.setLevel(logging.INFO)


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
    
    logger.info(f"Testing playground with {provider_cfg.display_name} - {model_cfg.name}")
    
    # Set up AI provider and test playground functionality
    playground_page.setup_ai_provider(provider_name, provider_cfg)
    playground_page.go_to_page()
    playground_page.select_model(provider_cfg.display_name, model_cfg.ui_selector)
    
    test_prompt = model_config_loader.get_test_prompt()
    playground_page.enter_prompt(test_prompt)
    playground_page.run_prompt()
    
    response = playground_page.get_response()
    logger.info(f"Response received (excerpt): {response[:100]}...")
    
    assert response, "No response was generated"
    assert not playground_page.has_error(), "Error message was displayed"
    assert len(response) > 50, "Response is too short to be valid"
    
    logger.info(f"Playground test completed successfully for {provider_cfg.display_name} - {model_cfg.name}")
    
    # Clean up
    playground_page.cleanup_ai_provider(provider_cfg)
