import time
import allure
import logging
import sys
from playwright.sync_api import Page
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ExperimentsPage import ExperimentsPage
from page_objects.PromptLibraryPage import PromptLibraryPage

logger = logging.getLogger(__name__)
logger.propagate = False
logger.handlers = []

# Add a single handler
handler = logging.StreamHandler(sys.stdout)
formatter = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s", "%H:%M:%S")
handler.setFormatter(formatter)
logger.addHandler(handler)
logger.setLevel(logging.INFO)


@allure.title("New account demo data")
def test_new_user_demo_data(page: Page, temp_user_with_api_key, env_config):
    """
    Test that newly created users can see demo data.
    This test uses the UI to verify the presence of demo data.

    The test will:
    1. Create a new user (via the fixture)
    2. Login with the new user credentials (handled by the fixture)
    3. Navigate to projects page to verify demo projects exist
    4. Navigate to datasets page to verify demo datasets exist
    5. Delete the user (handled by the fixture)
    """

    projects_page = ProjectsPage(page)
    projects_page.go_to_page()

    max_wait_time = 30  # seconds
    retry_interval = 2  # seconds
    start_time = time.time()

    logger.info("Waiting for demo data to generate")
    while True:
        try:
            projects_page.check_project_exists_on_current_page("Demo evaluation")
            projects_page.check_project_exists_on_current_page("Demo chatbot 🤖")
            break
        except Exception as e:
            elapsed_time = time.time() - start_time

            if elapsed_time > max_wait_time:
                raise AssertionError(
                    f"Demo data not found after {max_wait_time} seconds: {str(e)}"
                )

            projects_page.page.reload()
            time.sleep(retry_interval)
            logger.info(f"Retrying check... ({elapsed_time:.1f}s elapsed)")

    projects_page.check_project_exists_on_current_page("Demo chatbot 🤖")
    projects_page.click_project("Demo chatbot 🤖")

    logger.info("Checking demo project has generated traces")
    traces_page = TracesPage(page)
    assert (
        traces_page.get_number_of_traces_on_page() > 0
    ), "No traces generated for demo project"

    logger.info("Checking demo dataset generated")
    datasets_page = DatasetsPage(page)
    datasets_page.go_to_page()

    try:
        datasets_page.check_dataset_exists_on_page_by_name("Demo dataset")
    except Exception as _:
        raise AssertionError("Demo dataset not found")

    logger.info("Checking demo experiment generated")
    experiments_page = ExperimentsPage(page)
    experiments_page.go_to_page()
    try:
        experiments_page.check_experiment_exists_by_name("Demo experiment")
    except Exception as _:
        raise AssertionError("Demo experiment not found")

    logger.info("Checking demo prompt generated")
    prompts_page = PromptLibraryPage(page)
    prompts_page.go_to_page()
    try:
        prompts_page.check_prompt_exists_in_workspace("Q&A Prompt")
    except Exception as _:
        raise AssertionError("Demo prompt not found")
