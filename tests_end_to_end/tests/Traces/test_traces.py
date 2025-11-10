import pytest
from playwright.sync_api import Page, expect
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from .traces_config import PREFIX
from collections import Counter
from tests.sdk_helpers import (
    get_traces_of_project_sdk,
    delete_list_of_traces_sdk,
    wait_for_traces_to_be_visible,
)
import logging
import allure

logger = logging.getLogger(__name__)


class TestTracesCrud:
    @pytest.mark.parametrize("traces_number", [25])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    @pytest.mark.tracing
    @pytest.mark.regression
    @allure.title("Trace creation - {create_traces}")
    def test_traces_visibility(
        self, page: Page, create_project_api, traces_number, create_traces
    ):
        """Test basic trace creation via both decorator and low-level client, check visibility in both UI and SDK.

        Steps:
        1. Create project via fixture
        2. Create 25 traces via specified method (runs twice):
           - Via decorator
           - Via client
        3. Verify in UI:
           - All traces appear in project page
           - Names match creation convention
           - Count matches expected
        4. Verify via SDK:
           - All traces retrievable
           - Names match creation convention
           - Count matches expected
        """
        logger.info("Starting traces visibility test")
        project_name = create_project_api
        created_trace_names = Counter([PREFIX + str(i) for i in range(traces_number)])

        # Navigate to project traces
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

        # Create traces
        _ = create_traces

        # Wait for traces to appear on UI
        traces_page = TracesPage(page, traces_created=True)
        traces_page.wait_for_traces_to_be_visible()

        # Verify traces in UI
        logger.info("Verifying traces in UI")
        try:
            traces_ui = traces_page.get_all_trace_names_in_project()
            assert Counter(traces_ui) == created_trace_names, (
                f"UI traces mismatch.\n"
                f"Expected: {dict(created_trace_names)}\n"
                f"Got: {dict(Counter(traces_ui))}"
            )
            logger.info("Successfully verified traces in UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify traces in UI.\n"
                f"Project: {project_name}\n"
                f"Expected count: {traces_number}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify traces via SDK
        logger.info("Verifying traces via SDK")
        try:
            traces_sdk = get_traces_of_project_sdk(
                project_name=project_name, size=traces_number
            )
            traces_sdk_names = [trace["name"] for trace in traces_sdk]
            assert Counter(traces_sdk_names) == created_trace_names, (
                f"SDK traces mismatch.\n"
                f"Expected: {dict(created_trace_names)}\n"
                f"Got: {dict(Counter(traces_sdk_names))}"
            )
            logger.info("Successfully verified traces via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify traces via SDK.\n"
                f"Project: {project_name}\n"
                f"Expected count: {traces_number}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.parametrize("traces_number", [10])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    @pytest.mark.regression
    @pytest.mark.tracing
    @allure.title("Trace deletion in SDK - {create_traces}")
    def test_delete_traces_sdk(
        self, page: Page, create_project_api, traces_number, create_traces
    ):
        """Test trace deletion via SDK.

        Steps:
        1. Create project via fixture
        2. Create 10 traces via specified method (runs twice):
           - Via decorator
           - Via client
        3. Get initial traces list via SDK
        4. Delete first 2 traces via SDK
        5. Verify:
           - Traces removed from UI list
           - Traces no longer accessible via SDK
        """
        logger.info("Starting SDK trace deletion test")
        project_name = create_project_api

        # Create traces
        _ = create_traces

        # Get initial traces list
        logger.info("Getting initial traces list")
        try:
            wait_for_traces_to_be_visible(project_name=project_name, size=traces_number)
            traces_sdk = get_traces_of_project_sdk(
                project_name=project_name, size=traces_number
            )
            traces_to_delete = [
                {"id": trace["id"], "name": trace["name"]} for trace in traces_sdk[0:2]
            ]
            logger.info(
                f"Selected traces for deletion: {[t['name'] for t in traces_to_delete]}"
            )
        except Exception as e:
            raise AssertionError(
                f"Failed to get initial traces list.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Delete traces via SDK
        logger.info("Deleting traces via SDK")
        try:
            delete_list_of_traces_sdk(trace["id"] for trace in traces_to_delete)
            logger.info("Successfully deleted traces via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete traces via SDK.\n"
                f"Traces to delete: {[t['name'] for t in traces_to_delete]}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion in UI
        logger.info("Verifying traces removed from UI")
        try:
            projects_page = ProjectsPage(page)
            projects_page.go_to_page()
            projects_page.click_project(project_name)

            traces_page = TracesPage(page, traces_created=True)
            for trace in traces_to_delete:
                expect(
                    traces_page.page.get_by_role("row", name=trace["name"])
                ).not_to_be_visible()
            logger.info("Successfully verified traces not visible in UI")
        except Exception as e:
            raise AssertionError(
                f"Traces still visible in UI after deletion.\n"
                f"Traces: {[t['name'] for t in traces_to_delete]}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion via SDK
        logger.info("Verifying traces removed via SDK")
        try:
            traces_sdk = get_traces_of_project_sdk(
                project_name=project_name, size=traces_number
            )
            traces_sdk_names = [trace["name"] for trace in traces_sdk]
            deleted_names = [trace["name"] for trace in traces_to_delete]

            assert all(name not in traces_sdk_names for name in deleted_names), (
                f"Traces still exist after deletion.\n"
                f"Deleted traces: {deleted_names}\n"
                f"Current traces: {traces_sdk_names}"
            )
            logger.info("Successfully verified traces removed via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify traces deletion via SDK.\n"
                f"Traces to delete: {[t['name'] for t in traces_to_delete]}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.parametrize("traces_number", [10])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    @pytest.mark.tracing
    @pytest.mark.regression
    @allure.title("Trace deletion in UI - {create_traces}")
    def test_delete_traces_ui(
        self, page: Page, create_project_api, traces_number, create_traces
    ):
        """Testing trace deletion via UI interface.

        Steps:
        1. Create project via fixture
        2. Create 10 traces via specified method (runs twice):
           - Via decorator
           - Via client
        3. Get initial traces list via SDK
        4. Delete first 2 traces via UI
        5. Verify:
           - Traces removed from UI list
           - Traces no longer accessible via SDK
        """
        logger.info("Starting trace deletion test")
        project_name = create_project_api

        # Navigate to project traces
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

        # Create traces
        _ = create_traces

        # Get initial traces list
        logger.info("Getting initial traces list")
        try:
            wait_for_traces_to_be_visible(project_name=project_name, size=traces_number)
            traces_sdk = get_traces_of_project_sdk(
                project_name=project_name, size=traces_number
            )
            traces_sdk_names = [trace["name"] for trace in traces_sdk]
            traces_to_delete = traces_sdk_names[0:2]
            logger.info(f"Selected traces for deletion: {traces_to_delete}")
        except Exception as e:
            raise AssertionError(
                f"Failed to get initial traces list.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Delete traces via UI
        logger.info("Deleting traces via UI")
        traces_page = TracesPage(page, traces_created=True)
        traces_page.page.reload()

        # Wait for traces to appear on UI
        traces_page.wait_for_traces_to_be_visible()

        try:
            for trace_name in traces_to_delete:
                traces_page.delete_single_trace_by_name(trace_name)
                logger.info(f"Deleted trace '{trace_name}'")
            traces_page.page.wait_for_timeout(200)
        except Exception as e:
            raise AssertionError(
                f"Failed to delete traces via UI.\n"
                f"Traces to delete: {traces_to_delete}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion in UI
        logger.info("Verifying traces removed from UI")
        try:
            for trace_name in traces_to_delete:
                expect(
                    traces_page.page.get_by_role("row", name=trace_name)
                ).not_to_be_visible()
            logger.info("Successfully verified traces not visible in UI")
        except Exception as e:
            raise AssertionError(
                f"Traces still visible in UI after deletion.\n"
                f"Traces: {traces_to_delete}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion via SDK
        logger.info("Verifying traces removed via SDK")
        try:
            wait_for_traces_to_be_visible(project_name=project_name, size=traces_number)
            traces_sdk = get_traces_of_project_sdk(
                project_name=project_name, size=traces_number
            )
            traces_sdk_names = [trace["name"] for trace in traces_sdk]
            assert all(name not in traces_sdk_names for name in traces_to_delete), (
                f"Traces still exist after deletion.\n"
                f"Deleted traces: {traces_to_delete}\n"
                f"Current traces: {traces_sdk_names}"
            )
            logger.info("Successfully verified traces removed via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify traces deletion via SDK.\n"
                f"Traces to delete: {traces_to_delete}\n"
                f"Error: {str(e)}"
            ) from e
