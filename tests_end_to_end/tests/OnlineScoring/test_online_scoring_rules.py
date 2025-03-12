from playwright.sync_api import Page, expect
from page_objects.TracesPage import TracesPage
from page_objects.ProjectsPage import ProjectsPage
import logging
import allure

logger = logging.getLogger(__name__)


class TestOnlineScoringRules:
    @allure.title("Basic moderation rule creation")
    def test_create_moderation_rule(
        self,
        create_ai_provider_config,
        page: Page,
        create_project_api,
        create_10_test_traces,
    ):
        """Test creating a moderation scoring rule.

        Steps:
        1. Navigate to the traces page
        2. Open the rules tab
        3. Create a new hallucination rule
        4. Verify the rule appears in the list
        """
        logger.info("Starting moderation rule creation test")
        project_name = create_project_api

        # Navigate to traces page
        logger.info(f"Navigating to traces page for project {project_name}")
        traces_page = TracesPage(page)
        logger.info(f"Navigating to project '{project_name}'")
        projects_page = ProjectsPage(page)
        try:
            projects_page.go_to_page()
            projects_page.click_project(project_name)
            logger.info("Successfully navigated to project traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to project traces.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Navigate to rules tab
        logger.info("Navigating to rules tab")
        traces_page.page.get_by_role("tab", name="Online evaluation").click()

        # Create new rule
        logger.info("Creating new moderation rule")
        rule_name = "Test Moderation Rule"

        # Click create rule button
        traces_page.page.get_by_role("button", name="Create your first rule").click()

        # Fill rule details
        traces_page.page.get_by_placeholder("Rule name").fill(rule_name)

        # Set sampling rate to 100%
        sampling_value = traces_page.page.locator("#sampling_rate-input")
        sampling_value.fill("1")

        # Select model
        traces_page.page.get_by_role("combobox").filter(
            has_text="Select a LLM model"
        ).click()
        traces_page.page.get_by_text("OpenAI").hover()
        traces_page.page.get_by_label("GPT 4o Mini", exact=True).click()

        # Select hallucination template
        traces_page.page.get_by_role("combobox").filter(
            has_text="Custom LLM-as-judge"
        ).click()
        traces_page.page.get_by_label("Moderation", exact=True).click()

        # Fill in variable mapping
        variable_map = traces_page.page.get_by_placeholder(
            "Select a key from recent trace"
        )
        variable_map.click()
        variable_map.fill("output.output")
        traces_page.page.get_by_role("option", name="output.output").click()

        # Create rule
        traces_page.page.get_by_role("button", name="Create rule").click()

        # Verify rule was created
        logger.info(f"Verifying rule '{rule_name}' was created")
        expect(traces_page.page.get_by_text(rule_name)).to_be_visible()

        logger.info("Successfully created and verified moderation rule")
