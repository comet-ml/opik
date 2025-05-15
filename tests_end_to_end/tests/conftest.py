import pytest
import os
import opik
import tempfile
import time
import shutil
from opik import track, opik_context
from tests.config import EnvConfig, get_environment_config
from playwright.sync_api import Page, Browser, Playwright, expect
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ExperimentsPage import ExperimentsPage
from page_objects.PromptLibraryPage import PromptLibraryPage
from page_objects.AIProvidersConfigPage import AIProvidersConfigPage
from page_objects.FeedbackDefinitionsPage import FeedbackDefinitionsPage
from tests.sdk_helpers import (
    create_project_via_api,
    delete_project_by_name_sdk,
    get_random_string,
    wait_for_number_of_traces_to_be_visible,
    client_get_prompt_retries,
    find_project_by_name_sdk,
    wait_for_project_to_not_be_visible,
)
from utils import TEST_ITEMS
import re
import json
import allure
import logging


def pytest_addoption(parser):
    parser.addoption(
        "--show-requests",
        action="store_true",
        default=False,
        help="Show HTTP requests in test output",
    )


def pytest_configure(config):
    """This runs before any tests or fixtures are executed"""
    config.addinivalue_line("markers", "sanity: mark test as a sanity test")

    # Configure root logger only if it doesn't already have handlers
    root_logger = logging.getLogger()
    if not root_logger.handlers:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s [%(levelname)s] %(message)s",
            datefmt="%H:%M:%S",
        )

    # Set levels for specific loggers
    logging.getLogger("opik").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("requests").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)

    loggers_to_configure = [
        "opik",
        "urllib3",
        "requests",
        "httpx",
        "http.client",
    ]

    level = logging.INFO if config.getoption("--show-requests") else logging.WARNING
    for logger_name in loggers_to_configure:
        logging.getLogger(logger_name).setLevel(level)


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
def video_dir():
    """Create a temporary directory for videos"""
    # Create a temp directory for videos
    video_path = tempfile.mkdtemp(prefix="playwright_videos_")
    logging.info(f"Created video directory: {video_path}")

    # Dictionary to track test status and video paths
    video_info = {}

    # Store the video path and info dictionary in a container that can be accessed by other fixtures
    container = {"path": video_path, "info": video_info}

    yield container

    # Clean up videos for successful tests
    for test_id, info in video_info.items():
        if not info.get("failed", False) and info.get("video_path"):
            try:
                video_file = info["video_path"]
                if os.path.exists(video_file):
                    os.remove(video_file)
                    logging.info(f"Removed video for successful test: {test_id}")
            except Exception as e:
                logging.warning(
                    f"Failed to remove video for successful test {test_id}: {str(e)}"
                )

    # Clean up the directory at the end of the session
    if os.path.exists(video_path):
        try:
            # Wait a bit to ensure all files are released
            time.sleep(1)
            shutil.rmtree(video_path)
            logging.info(f"Cleaned up video directory: {video_path}")
        except Exception as e:
            logging.warning(f"Failed to clean up video directory: {str(e)}")


@pytest.fixture(scope="session")
def browser_context(browser: Browser, env_config, video_dir):
    """Create a browser context with required permissions and authentication"""
    # Enable video recording
    context = browser.new_context(
        record_video_dir=video_dir["path"],
        record_video_size={"width": 1280, "height": 720},
    )

    # Store the video info container for access by other fixtures
    context._video_info = video_dir["info"]

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

    return env_config


@pytest.fixture(scope="function")
def set_project_name(env_config: EnvConfig) -> str:
    os.environ["OPIK_PROJECT_NAME"] = env_config.project_name = (
        "project_" + get_random_string(5)
    )

    return env_config.project_name


@pytest.fixture(autouse=True)
def setup_logging(caplog):
    caplog.set_level(logging.INFO)


@pytest.fixture(scope="session", autouse=True)
def configure_logging(request):
    """Additional logging setup that runs before any tests"""
    opik_logger = logging.getLogger("opik")
    if not request.config.getoption("--show-requests"):
        opik_logger.setLevel(logging.ERROR)
        logging.getLogger("urllib3").setLevel(logging.ERROR)


@pytest.fixture(scope="function", autouse=True)
def client(set_project_name, env_config: EnvConfig) -> opik.Opik:
    """Create an Opik client configured for the current environment"""
    kwargs = {
        "workspace": env_config.workspace,
        "host": env_config.api_url,  # SDK expects the full API URL
        "project_name": set_project_name,
    }
    if env_config.api_key:
        kwargs["api_key"] = env_config.api_key
    return opik.Opik(**kwargs)


@pytest.fixture(scope="function")
def page(browser_context, request):
    """Create a new page with authentication already handled"""
    page = browser_context.new_page()

    test_id = f"{request.node.name}_{id(request)}"
    test_name = request.node.name

    console_logs = []

    def log_handler(msg):
        console_logs.append(f"{msg.type}: {msg.text}")

    page.on("console", log_handler)

    page._console_messages = console_logs
    page._test_name = test_name
    page._test_id = test_id

    if hasattr(browser_context, "_video_info"):
        browser_context._video_info[test_id] = {"failed": False, "video_path": None}

    yield page

    failed = False
    try:
        for report in getattr(request.node, "_reports", {}).values():
            if report.failed:
                failed = True
                break
    except Exception:
        pass

    video_path = None
    if hasattr(page, "video") and page.video:
        try:
            video_path = page.video.path()
        except Exception as e:
            logging.error(f"Failed to get video path: {str(e)}")

    page.close()

    if (
        hasattr(browser_context, "_video_info")
        and test_id in browser_context._video_info
    ):
        browser_context._video_info[test_id]["failed"] = failed
        if video_path:
            browser_context._video_info[test_id]["video_path"] = video_path

    if failed and video_path:
        try:
            max_retries = 10
            retry_count = 0

            while retry_count < max_retries:
                if os.path.exists(video_path):
                    try:
                        with open(video_path, "rb") as f:
                            video_content = f.read()

                        allure.attach(
                            video_content,
                            name=f"Test Failure Video - {test_name}",
                            attachment_type=allure.attachment_type.WEBM,
                        )
                        logging.info(
                            f"Successfully attached video for failed test: {test_name}"
                        )
                        break
                    except Exception as e:
                        logging.error(f"Failed to attach video content: {str(e)}")
                        break

                retry_count += 1
                time.sleep(0.5)

            if retry_count >= max_retries:
                logging.warning(f"Video file not found after waiting: {video_path}")

        except Exception as e:
            logging.error(f"Failed to handle video in page fixture: {str(e)}")


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
@allure.title("Create project via API call, handle cleanup after test")
def create_project_api():
    """
    Create a project via SDK and handle cleanup.
    Checks if project exists before attempting deletion.
    """
    proj_name = os.environ["OPIK_PROJECT_NAME"]
    if find_project_by_name_sdk(proj_name):
        delete_project_by_name_sdk(proj_name)
    create_project_via_api(name=proj_name)
    yield proj_name

    if find_project_by_name_sdk(proj_name):
        delete_project_by_name_sdk(proj_name)


@pytest.fixture(scope="function")
@allure.title("Create project via UI, handle cleanup after test")
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
@allure.title("Log 5 traces with spans and subspans using the low level Opik client")
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
@allure.title("Log 5 traces with spans and subspans using the @track decorator")
def log_traces_with_spans_decorator():
    """
    Log 5 traces with spans and subspans using the @track decorator
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
@allure.title("Log threads using the @track decorator")
def log_threads_with_decorator():
    """
    Log 3 threads with 3 inputs and 3 ouputs using the @track decorator
    """

    thread1_config = {
        "thread_id": "thread_1",
        "inputs": ["input1_1", "input1_2", "input1_3"],
        "outputs": ["output1_1", "output1_2", "output1_3"],
    }

    thread2_config = {
        "thread_id": "thread_2",
        "inputs": ["input2_1", "input2_2", "input2_3"],
        "outputs": ["output2_1", "output2_2", "output2_3"],
    }

    thread3_config = {
        "thread_id": "thread_3",
        "inputs": ["input3_1", "input3_2", "input3_3"],
        "outputs": ["output3_1", "output3_2", "output3_3"],
    }

    thread_configs = [thread1_config, thread2_config, thread3_config]

    @opik.track
    def chat_message(input, output, thread_id):
        opik_context.update_current_trace(thread_id=thread_id)
        return output

    for thread in thread_configs:
        for input, output in zip(thread["inputs"], thread["outputs"]):
            chat_message(input, output, thread["thread_id"])
    yield thread_configs


@pytest.fixture(scope="function")
@allure.title("Log threads using low level SDK")
def log_threads_low_level(client: opik.Opik):
    """
    Log 3 threads with 3 inputs and 3 ouputs using low level SDK
    """

    thread1_config = {
        "thread_id": "thread_1",
        "inputs": ["input1_1", "input1_2", "input1_3"],
        "outputs": ["output1_1", "output1_2", "output1_3"],
    }

    thread2_config = {
        "thread_id": "thread_2",
        "inputs": ["input2_1", "input2_2", "input2_3"],
        "outputs": ["output2_1", "output2_2", "output2_3"],
    }

    thread3_config = {
        "thread_id": "thread_3",
        "inputs": ["input3_1", "input3_2", "input3_3"],
        "outputs": ["output3_1", "output3_2", "output3_3"],
    }

    thread_configs = [thread1_config, thread2_config, thread3_config]

    for thread in thread_configs:
        for input, output in zip(thread["inputs"], thread["outputs"]):
            client.trace(
                name="chat_conversation",
                input=input,
                output=output,
                thread_id=thread["thread_id"],
            )

    yield thread_configs


@pytest.fixture(scope="function")
@allure.title("Create dataset via SDK, handle cleanup after test")
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
@allure.title("Create dataset via UI, handle cleanup after test")
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


@pytest.fixture
@allure.title("Create a dataset and insert 10 test items via the SDK")
def insert_dataset_items_sdk(client: opik.Opik, create_dataset_sdk):
    dataset = client.get_dataset(create_dataset_sdk)
    dataset.insert(TEST_ITEMS)


@pytest.fixture
@allure.title("Create a prompt via the SDK")
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
@allure.title("Create a prompt via the UI")
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
@allure.title("Create a project and log 10 test traces via the low-level client")
def create_10_test_traces(page: Page, client, create_project_api):
    proj_name = create_project_api
    for i in range(10):
        _ = client.trace(
            name=f"trace{i}",
            project_name=proj_name,
            input={"input": "test input", "context": "test context"},
            output={"output": "test output"},
        )
    wait_for_number_of_traces_to_be_visible(project_name=proj_name, number_of_traces=10)
    yield


@pytest.fixture
@allure.title("Create a categorical feedback definition via the UI")
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
@allure.title("Create a numerical feedback definition via the UI")
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


@pytest.fixture
@allure.title("Create an AI provider config via the UI")
def create_ai_provider_config(page: Page):
    ai_providers_page = AIProvidersConfigPage(page)
    ai_providers_page.go_to_page()
    ai_providers_page.add_provider(
        provider_type="openai", api_key=os.environ["OPENAI_API_KEY"]
    )
    yield
    ai_providers_page.go_to_page()
    ai_providers_page.delete_provider(provider_name="OPENAI_API_KEY")


@pytest.fixture
def create_moderation_rule_fixture(
    create_ai_provider_config, create_10_test_traces, page: Page, create_project_api
):
    project_name = create_project_api

    # Navigate to traces page
    traces_page = TracesPage(page)
    projects_page = ProjectsPage(page)
    try:
        projects_page.go_to_page()
        projects_page.click_project(project_name)
    except Exception as e:
        raise AssertionError(
            f"Failed to navigate to project traces.\n"
            f"Project name: {project_name}\n"
            f"Error: {str(e)}"
        ) from e

    try:
        expect(
            traces_page.page.get_by_role("tab", name="Online evaluation")
        ).to_be_visible()
    except Exception as e:
        raise AssertionError(
            f"Rules tab not found, possible error loading" f"Error: {str(e)}"
        ) from e

    traces_page.page.get_by_role("tab", name="Online evaluation").click()
    traces_page.page.get_by_role("tab", name="Online evaluation").click()
    rule_name = "Test Moderation Rule"
    traces_page.page.get_by_role("button", name="Create your first rule").click()
    traces_page.page.get_by_placeholder("Rule name").fill(rule_name)
    sampling_value = traces_page.page.locator("#sampling_rate-input")
    sampling_value.fill("1")

    traces_page.page.get_by_role("combobox").filter(
        has_text="Select a LLM model"
    ).click()
    traces_page.page.get_by_text("OpenAI").hover()
    traces_page.page.get_by_label("GPT 4o Mini", exact=True).click()

    traces_page.page.get_by_role("combobox").filter(
        has_text="Custom LLM-as-judge"
    ).click()
    traces_page.page.get_by_label("Moderation", exact=True).click()

    variable_map = traces_page.page.get_by_placeholder("Select a key from recent trace")
    variable_map.click()
    variable_map.fill("output.output")
    traces_page.page.get_by_role("option", name="output.output").click()

    traces_page.page.get_by_role("button", name="Create rule").click()

    yield

    traces_page.page.get_by_role("button", name="Columns").click()
    traces_page.page.get_by_role("menuitem", name="Show all").click()
    traces_page.page.wait_for_timeout(500)


# Hook for capturing screenshots and videos on test failures
@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """
    Capture screenshots and videos when tests fail and attach them to Allure reports.
    This hook runs around each test phase (setup, call, teardown).
    """
    outcome = yield
    report = outcome.get_result()

    # Store the report for later use
    if not hasattr(item, "_reports"):
        item._reports = {}
    item._reports[report.when] = report

    # Only capture for failed tests
    if report.when == "call" and report.failed:
        try:
            # Get the page fixture if it exists
            page = item.funcargs.get("page", None)
            if page:
                # Take screenshot
                screenshot_name = f"failure_{item.name}_{int(time.time())}.png"
                screenshot_path = os.path.join(os.getcwd(), screenshot_name)

                try:
                    page.screenshot(path=screenshot_path)
                    if os.path.exists(screenshot_path):
                        # Attach screenshot to Allure report
                        allure.attach.file(
                            screenshot_path,
                            name="Screenshot on Failure",
                            attachment_type=allure.attachment_type.PNG,
                        )
                        # Clean up the file after attaching
                        os.remove(screenshot_path)
                except Exception as e:
                    logging.error(f"Failed to capture screenshot: {str(e)}")

                # Capture HTML source
                try:
                    html_content = page.content()
                    allure.attach(
                        html_content,
                        name="Page HTML on Failure",
                        attachment_type=allure.attachment_type.HTML,
                    )
                except Exception as e:
                    logging.error(f"Failed to capture HTML: {str(e)}")

                # Capture console logs
                try:
                    # Get console logs if available
                    if hasattr(page, "_console_messages") and page._console_messages:
                        allure.attach(
                            "\n".join(page._console_messages),
                            name="Browser Console Logs",
                            attachment_type=allure.attachment_type.TEXT,
                        )
                except Exception as e:
                    logging.error(f"Failed to capture console logs: {str(e)}")

        except Exception as e:
            logging.error(f"Failed to capture failure evidence: {str(e)}")
