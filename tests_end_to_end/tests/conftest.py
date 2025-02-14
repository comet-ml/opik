import pytest
import os
import opik
from opik import track, opik_context
from tests.config import EnvConfig, get_environment_config
from playwright.sync_api import Page, Browser, Playwright
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ExperimentsPage import ExperimentsPage
from page_objects.PromptLibraryPage import PromptLibraryPage
from page_objects.FeedbackDefinitionsPage import FeedbackDefinitionsPage
from tests.sdk_helpers import (
    create_project_api,
    delete_project_by_name_sdk,
    wait_for_number_of_traces_to_be_visible,
    client_get_prompt_retries,
    find_project_by_name_sdk,
    wait_for_project_to_not_be_visible,
)
from utils import TEST_ITEMS
import re
import json


def pytest_configure(config):
    config.addinivalue_line("markers", "sanity: mark test as a sanity test")


@pytest.fixture(scope="session")
def browser(playwright: Playwright, request) -> Browser:
    browser_name = request.config.getoption("--browser", default="chromium")
    if isinstance(browser_name, list):
        browser_name = browser_name[0]
    browser_type = getattr(playwright, browser_name)
    browser = browser_type.launch(slow_mo=200)
    yield browser
    browser.close()


@pytest.fixture(scope="session")
def browser_context(browser: Browser, env_config: EnvConfig):
    """Create a browser context with required permissions and authentication"""
    context = browser.new_context()
    context.grant_permissions(["clipboard-read", "clipboard-write"])

    # Handle cloud environment authentication
    if not env_config.base_url.startswith("http://localhost"):
        page = context.new_page()
        # Extract base URL for authentication (remove /opik from the end)
        base_url = re.sub(r"/opik$", "", env_config.base_url)
        auth_url = f"{base_url}/api/auth/login"

        # Perform login
        response = page.request.post(
            auth_url,
            data=json.dumps(
                {
                    "email": env_config.test_user_email,
                    "plainTextPassword": env_config.test_user_password,
                }
            ),
            headers={"Content-Type": "application/json"},
        )

        if response.status != 200:
            raise Exception(
                f"Login failed with status {response.status}: {response.text()}"
            )

        # Extract API key from login response
        response_data = response.json()
        if "apiKeys" not in response_data or not response_data["apiKeys"]:
            print("RESPONSE DATA IS", response_data)
            raise Exception("No API keys found in login response")

        # Set the API key in environment
        os.environ["OPIK_API_KEY"] = response_data["apiKeys"][0]
        # Update env_config with the API key
        env_config.api_key = response_data["apiKeys"][0]

        page.close()

    yield context
    context.close()


@pytest.fixture(scope="session")
def env_config() -> EnvConfig:
    """
    Get the environment configuration from environment variables.
    """
    env_config = get_environment_config()
    # Set base URL and API URL override
    os.environ["OPIK_BASE_URL"] = env_config.base_url
    os.environ["OPIK_URL_OVERRIDE"] = env_config.api_url

    # Set workspace and project
    os.environ["OPIK_WORKSPACE"] = env_config.workspace
    os.environ["OPIK_PROJECT_NAME"] = env_config.project_name

    return env_config


@pytest.fixture(scope="session", autouse=True)
def client(env_config: EnvConfig, browser_context) -> opik.Opik:
    """Create an Opik client configured for the current environment"""
    kwargs = {
        "workspace": env_config.workspace,
        "host": env_config.api_url,  # SDK expects the full API URL
    }
    if env_config.api_key:
        kwargs["api_key"] = env_config.api_key
    return opik.Opik(**kwargs)


@pytest.fixture(scope="function")
def page(browser_context):
    """Create a new page with authentication already handled"""
    page = browser_context.new_page()
    yield page
    page.close()


@pytest.fixture(scope="function")
def projects_page(page):
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    return projects_page


@pytest.fixture(scope="function")
def projects_page_timeout(page) -> ProjectsPage:
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.page.wait_for_timeout(10000)
    return projects_page


@pytest.fixture(scope="function")
def traces_page(page, projects_page, config):
    projects_page.click_project(config["project"]["name"])
    traces_page = TracesPage(page)
    return traces_page


@pytest.fixture(scope="function")
def datasets_page(page):
    datasets_page = DatasetsPage(page)
    datasets_page.go_to_page()
    return datasets_page


@pytest.fixture(scope="function")
def experiments_page(page):
    experiments_page = ExperimentsPage(page)
    experiments_page.go_to_page()
    return experiments_page


@pytest.fixture(scope="function")
def create_project(page: Page):
    """
    Create a project via SDK and handle cleanup.
    Checks if project exists before attempting deletion.
    """
    proj_name = os.environ["OPIK_PROJECT_NAME"]
    if find_project_by_name_sdk(proj_name):
        delete_project_by_name_sdk(proj_name)
    create_project_api(name=proj_name)
    yield proj_name

    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    try:
        projects_page.search_project(proj_name)
        projects_page.check_project_not_exists_on_current_page(project_name=proj_name)
    except AssertionError as _:
        projects_page.delete_project_by_name(proj_name)
    wait_for_project_to_not_be_visible(proj_name)


@pytest.fixture(scope="function")
def create_project_ui(page: Page):
    """
    Create a project via UI and handle cleanup.
    Checks if project exists before attempting deletion.
    """
    proj_name = os.environ["OPIK_PROJECT_NAME"]
    if find_project_by_name_sdk(proj_name):
        delete_project_by_name_sdk(proj_name)
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.create_new_project(project_name=proj_name)

    yield proj_name

    projects_page.go_to_page()
    try:
        projects_page.search_project(proj_name)
        projects_page.check_project_not_exists_on_current_page(project_name=proj_name)
    except AssertionError as _:
        projects_page.delete_project_by_name(proj_name)
    wait_for_project_to_not_be_visible(proj_name)


@pytest.fixture(scope="function")
def log_traces_with_spans_low_level(client: opik.Opik):
    """
    Log 5 traces with spans and subspans using the low level Opik client
    Each should have their own names, tags, metadata and feedback scores to test integrity of data transmitted
    """

    trace_config = {
        "count": 5,
        "prefix": "client-trace-",
        "tags": ["c-tag1", "c-tag2"],
        "metadata": {"c-md1": "val1", "c-md2": "val2"},
        "feedback_scores": [
            {"name": "c-score1", "value": 0.1},
            {"name": "c-score2", "value": 7},
        ],
    }

    span_config = {
        "count": 2,
        "prefix": "client-span-",
        "tags": ["d-span1", "d-span2"],
        "metadata": {"d-md1": "val1", "d-md2": "val2"},
        "feedback_scores": [
            {"name": "s-score1", "value": 0.93},
            {"name": "s-score2", "value": 2},
        ],
    }

    for trace_index in range(trace_config["count"]):  # type: ignore
        client_trace = client.trace(
            name=trace_config["prefix"] + str(trace_index),  # type: ignore
            input={"input": f"input-{trace_index}"},
            output={"output": f"output-{trace_index}"},
            tags=trace_config["tags"],
            metadata=trace_config["metadata"],
            feedback_scores=trace_config["feedback_scores"],
            project_name=os.environ["OPIK_PROJECT_NAME"],
        )
        for span_index in range(span_config["count"]):  # type: ignore
            client_span = client_trace.span(
                name=span_config["prefix"] + str(span_index),  # type: ignore
                input={"input": f"input-{span_index}"},
                output={"output": f"output-{span_index}"},
                tags=span_config["tags"],
                metadata=span_config["metadata"],
            )
            for score in span_config["feedback_scores"]:  # type: ignore
                client_span.log_feedback_score(name=score["name"], value=score["value"])

    wait_for_number_of_traces_to_be_visible(
        project_name=os.environ["OPIK_PROJECT_NAME"], number_of_traces=5
    )
    yield trace_config, span_config


@pytest.fixture(scope="function")
def log_traces_with_spans_decorator():
    """
    Log 5 traces with spans and subspans using the low level Opik client
    Each should have their own names, tags, metadata and feedback scores to test integrity of data transmitted
    """

    trace_config = {
        "count": 5,
        "prefix": "decorator-trace-",
        "tags": ["d-tag1", "d-tag2"],
        "metadata": {"d-md1": "val1", "d-md2": "val2"},
        "feedback_scores": [
            {"name": "d-score1", "value": 0.1},
            {"name": "d-score2", "value": 7},
        ],
    }

    span_config = {
        "count": 2,
        "prefix": "decorator-span-",
        "tags": ["d-span1", "d-span2"],
        "metadata": {"d-md1": "val1", "d-md2": "val2"},
        "feedback_scores": [
            {"name": "s-score1", "value": 0.93},
            {"name": "s-score2", "value": 2},
        ],
    }

    @track(project_name=os.environ["OPIK_PROJECT_NAME"])
    def make_span(x):
        opik_context.update_current_span(
            name=span_config["prefix"] + str(x),
            input={"input": f"input-{x}"},
            metadata=span_config["metadata"],
            tags=span_config["tags"],
            feedback_scores=span_config["feedback_scores"],
        )
        return {"output": f"output-{x}"}

    @track(project_name=os.environ["OPIK_PROJECT_NAME"])
    def make_trace(x):
        for spans_no in range(span_config["count"]):
            make_span(spans_no)

        opik_context.update_current_trace(
            name=trace_config["prefix"] + str(x),
            input={"input": f"input-{x}"},
            metadata=trace_config["metadata"],
            tags=trace_config["tags"],
            feedback_scores=trace_config["feedback_scores"],
        )
        return {"output": f"output-{x}"}

    for x in range(trace_config["count"]):
        make_trace(x)

    wait_for_number_of_traces_to_be_visible(
        project_name=os.environ["OPIK_PROJECT_NAME"], number_of_traces=5
    )
    yield trace_config, span_config


@pytest.fixture(scope="function")
def create_dataset_sdk(client: opik.Opik):
    dataset_name = "automated_tests_dataset"
    client.create_dataset(name=dataset_name)
    yield dataset_name
    try:
        client.get_dataset(name=dataset_name)
        client.delete_dataset(name=dataset_name)
    except Exception as _:
        pass


@pytest.fixture(scope="function")
def create_dataset_ui(page: Page, client: opik.Opik):
    dataset_name = "automated_tests_dataset"
    datasets_page = DatasetsPage(page)
    datasets_page.go_to_page()
    datasets_page.create_dataset_by_name(dataset_name=dataset_name)

    yield dataset_name
    try:
        client.get_dataset(name=dataset_name)
        client.delete_dataset(name=dataset_name)
    except Exception as _:
        pass


@pytest.fixture(scope="function")
def create_dataset_sdk_no_cleanup(client: opik.Opik):
    dataset_name = "automated_tests_dataset"
    client.create_dataset(name=dataset_name)
    yield dataset_name


@pytest.fixture(scope="function")
def create_dataset_ui_no_cleanup(page: Page):
    dataset_name = "automated_tests_dataset"
    datasets_page = DatasetsPage(page)
    datasets_page.go_to_page()
    datasets_page.create_dataset_by_name(dataset_name=dataset_name)
    yield dataset_name


@pytest.fixture
def insert_dataset_items_sdk(client: opik.Opik, create_dataset_sdk):
    dataset = client.get_dataset(create_dataset_sdk)
    dataset.insert(TEST_ITEMS)


@pytest.fixture
def create_prompt_sdk(client: opik.Opik, page: Page):
    prompt = client.create_prompt(name="test_prompt", prompt="this is a test prompt")
    yield prompt
    prompt_library_page = PromptLibraryPage(page)
    prompt_library_page.go_to_page()
    try:
        prompt_library_page.check_prompt_not_exists_in_workspace(
            prompt_name="test_prompt"
        )
    except AssertionError as _:
        prompt_library_page.delete_prompt_by_name(prompt.name)


@pytest.fixture
def create_prompt_ui(client: opik.Opik, page: Page):
    prompt_library_page = PromptLibraryPage(page)
    prompt_library_page.go_to_page()
    prompt_library_page.create_new_prompt(
        name="test_prompt", prompt="this is a test prompt"
    )
    prompt = client_get_prompt_retries(
        client=client, prompt_name="test_prompt", timeout=10, initial_delay=1
    )
    yield prompt
    prompt_library_page = PromptLibraryPage(page)
    prompt_library_page.go_to_page()
    try:
        prompt_library_page.check_prompt_not_exists_in_workspace(
            prompt_name="test_prompt"
        )
    except AssertionError as _:
        prompt_library_page.delete_prompt_by_name(prompt.name)


@pytest.fixture
def create_10_test_traces(page: Page, client, create_project):
    proj_name = create_project
    for i in range(10):
        _ = client.trace(
            name=f"trace{i}",
            project_name=proj_name,
            input={"input": "test input"},
            output={"output": "test output"},
        )
    wait_for_number_of_traces_to_be_visible(project_name=proj_name, number_of_traces=10)
    yield


@pytest.fixture
def create_feedback_definition_categorical_ui(client: opik.Opik, page: Page):
    feedbacks_page = FeedbackDefinitionsPage(page)
    feedbacks_page.go_to_page()
    feedbacks_page.create_new_feedback(
        feedback_name="feedback_c_test", feedback_type="categorical"
    )

    # passing it to the test as a mutable type to cover name change edits
    data = {"name": "feedback_c_test"}
    yield data
    try:
        feedbacks_page.check_feedback_not_exists_by_name(feedback_name=data["name"])
    except AssertionError as _:
        feedbacks_page.delete_feedback_by_name(data["name"])


@pytest.fixture
def create_feedback_definition_numerical_ui(client: opik.Opik, page: Page):
    feedbacks_page = FeedbackDefinitionsPage(page)
    feedbacks_page.go_to_page()
    feedbacks_page.create_new_feedback(
        feedback_name="feedback_n_test", feedback_type="numerical"
    )

    # passing it to the test as a mutable type to cover name change edits
    data = {"name": "feedback_n_test"}
    yield data
    try:
        feedbacks_page.check_feedback_not_exists_by_name(feedback_name=data["name"])
    except AssertionError as _:
        feedbacks_page.delete_feedback_by_name(data["name"])
