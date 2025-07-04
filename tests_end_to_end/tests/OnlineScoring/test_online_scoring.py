import pytest
from playwright.sync_api import Page, expect
from page_objects.TracesPage import TracesPage
from page_objects.ProjectsPage import ProjectsPage
from tests.sdk_helpers import wait_for_number_of_traces_to_be_visible
from tests.model_config_loader import model_config_loader
import opik
import os
import logging
import allure

logger = logging.getLogger(__name__)


def pytest_generate_tests(metafunc):
    """Generate test parameters for all enabled online scoring models"""
    if "model_config" in metafunc.fixturenames:
        models = model_config_loader.get_enabled_models_for_online_scoring()
        if not models:
            pytest.skip("No enabled models found for online scoring testing")
        
        # Create test parameters with meaningful IDs
        test_params = []
        test_ids = []
        
        for provider_name, model_config, provider_config in models:
            test_params.append((provider_name, model_config, provider_config))
            test_ids.append(f"{provider_name}-{model_config.name.replace(' ', '_')}")
        
        metafunc.parametrize("model_config", test_params, ids=test_ids)


class TestOnlineScoring:
    @pytest.mark.regression
    @pytest.mark.online_scoring
    @allure.title("Basic moderation rule creation")
    def test_create_moderation_rule(
        self,
        model_config,
        page: Page,
        create_project_api,
        create_10_test_traces,
    ):
        """Test creating a moderation scoring rule for multiple models.

        Steps:
        1. Set up AI provider configuration
        2. Navigate to the traces page
        3. Open the rules tab
        4. Create a new moderation rule with the specified model
        5. Verify the rule appears in the list
        """
        provider_name, model_cfg, provider_cfg = model_config
        
        logger.info(f"Starting moderation rule creation test for {provider_cfg.display_name} - {model_cfg.name}")
        project_name = create_project_api
        
        # Set up AI provider and create moderation rule
        from page_objects.helpers.AIProviderSetupHelper import AIProviderSetupHelper
        from page_objects.ModerationRulesPage import ModerationRulesPage
        
        # Set up AI provider
        ai_setup_helper = AIProviderSetupHelper(page)
        ai_setup_helper.setup_provider_if_needed(provider_name, provider_cfg)
        
        # Navigate to project traces
        traces_page = TracesPage(page)
        traces_page.navigate_to_project(project_name)
        
        # Create moderation rule
        rules_page = ModerationRulesPage(page)
        rules_page.navigate_to_rules_tab()
        rule_name = f"Test Moderation Rule - {model_cfg.name}"
        rules_page.create_moderation_rule(rule_name, provider_cfg, model_cfg)

        # Verify rule was created
        logger.info(f"Verifying rule '{rule_name}' was created")
        expect(page.get_by_text(rule_name)).to_be_visible()

        logger.info(f"Successfully created and verified moderation rule for {provider_cfg.display_name} - {model_cfg.name}")
        
        # Clean up
        ai_setup_helper.cleanup_provider(provider_cfg)

    @pytest.mark.regression
    @pytest.mark.online_scoring
    @allure.title("Basic online scoring Moderation full flow")
    def test_online_scoring_basic_moderation(
        self,
        page: Page,
        client: opik.Opik,
        create_project_api,
        model_config,
        create_10_test_traces,
    ):
        provider_name, model_cfg, provider_cfg = model_config
        project_name = create_project_api
        
        logger.info(f"Testing online scoring moderation full flow for {provider_cfg.display_name} - {model_cfg.name}")
        
        # Set up AI provider and create moderation rule
        from page_objects.helpers.AIProviderSetupHelper import AIProviderSetupHelper
        from page_objects.ModerationRulesPage import ModerationRulesPage
        
        # Set up AI provider
        ai_setup_helper = AIProviderSetupHelper(page)
        ai_setup_helper.setup_provider_if_needed(provider_name, provider_cfg)
        
        # Navigate to project traces and create moderation rule
        traces_page = TracesPage(page)
        traces_page.navigate_to_project(project_name)
        
        rules_page = ModerationRulesPage(page)
        rules_page.navigate_to_rules_tab()
        rule_name = f"Test Moderation Rule - {model_cfg.name}"
        rules_page.create_moderation_rule(rule_name, provider_cfg, model_cfg)
        
        # Create additional traces for testing scoring
        for i in range(10):
            _ = client.trace(
                name=f"trace{i}",
                project_name=os.environ["OPIK_PROJECT_NAME"],
                input={"input": "test input", "context": "test context"},
                output={"output": "test output"},
            )
        wait_for_number_of_traces_to_be_visible(
            project_name=os.environ["OPIK_PROJECT_NAME"], number_of_traces=10
        )

        # Navigate to project traces
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(os.environ["OPIK_PROJECT_NAME"])
        logger.info("Successfully navigated to project traces")

        # Wait for moderation cell with retries
        max_retries = 5
        retry_delay = 2  # seconds

        for attempt in range(max_retries):
            try:
                traces_page.page.reload()
                expect(
                    traces_page.page.get_by_role("cell", name="Moderation")
                ).to_be_visible()
                break
            except Exception as e:
                logger.warning(f"Attempt {attempt + 1} failed: {str(e)}")
                if attempt < max_retries:
                    traces_page.page.wait_for_timeout(retry_delay * 1000)
                    continue
                raise AssertionError(
                    f"Failed to find Moderation cell after {max_retries} attempts.\n"
                    f"Project name: {os.environ["OPIK_PROJECT_NAME"]}\n"
                    f"Last error: {str(e)}"
                ) from e

        # Filter columns to show only ID and Moderation
        traces_page.page.get_by_role("button", name="Columns").click()
        traces_page.page.get_by_role("menuitem", name="Hide all").click()
        traces_page.page.get_by_role("button", name="Columns").click()
        traces_page.page.get_by_role("button", name="Moderation").click()

        max_retries = 10
        retry_delay = 3  # seconds

        for attempt in range(max_retries):
            moderation_cells = traces_page.page.get_by_role(
                "cell", name="0", exact=True
            )
            cell_count = moderation_cells.count()

            if cell_count == 10:
                logger.info(
                    "All 10 traces have been properly scored according to the rule"
                )
                break

            logger.info(
                f"Found {cell_count} moderation cells with value 0 (attempt {attempt + 1})"
            )

            if attempt < max_retries:
                traces_page.page.reload()
                traces_page.page.wait_for_timeout(retry_delay * 1000)
                continue

            raise AssertionError(
                f"Failed to find 10 moderation cells with value 0 after {max_retries} attempts"
            )
        
        logger.info(f"Online scoring moderation full flow completed successfully for {provider_cfg.display_name} - {model_cfg.name}")
        
        # Clean up
        ai_setup_helper.cleanup_provider(provider_cfg)
