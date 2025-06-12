from playwright.sync_api import Page, expect
from page_objects.TracesPage import TracesPage
from page_objects.ProjectsPage import ProjectsPage
from sdk_helpers import wait_for_number_of_traces_to_be_visible
import opik
import os
import logging
import allure

logger = logging.getLogger(__name__)


class TestOnlineScoring:
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
        try:
            expect(
                traces_page.page.get_by_role("tab", name="Online evaluation")
            ).to_be_visible()
        except Exception as e:
            raise AssertionError(
                f"Rules tab not found, possible error loading" f"Error: {str(e)}"
            ) from e

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

    @allure.title("Basic online scoring Moderation full flow")
    def test_online_scoring_basic_moderation(
        self,
        page: Page,
        client: opik.Opik,
        create_project_api,
        create_moderation_rule_fixture,
        create_10_test_traces,
    ):
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

        logger.info(
            f"Navigating to traces page for project {os.environ["OPIK_PROJECT_NAME"]}"
        )
        traces_page = TracesPage(page)
        logger.info(f"Navigating to project '{os.environ["OPIK_PROJECT_NAME"]}'")
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
