import allure
import logging

from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from page_objects.TracesPageSpansMenu import TracesPageSpansMenu

logger = logging.getLogger(__name__)


class TestTraceSpans:
    @allure.title("Attachments in traces - log_trace_attachment_low_level")
    def test_attachments_low_level(
        self, page, create_project_api, log_trace_attachment_low_level
    ):
        """Test attachment visibility.

        Steps:
        1. Create project via fixture
        2. Create traces and spans with attachment (audio, video, etc) via specified method:
           - Via low-level client
        3. Navigate to project traces page
        4. Check attachment for trace
        """
        logger.info("Starting threads creation test")
        project_name = create_project_api

        # Navigate to project
        logger.info(f"Navigating to project '{project_name}'")
        projects_page = ProjectsPage(page)

        try:
            projects_page.go_to_page()
            projects_page.click_project(project_name)
            logger.info("Successfully navigated to project")

        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to project.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Wait for traces to appear on UI
        traces_page = TracesPage(page)
        traces_page.wait_for_traces_to_be_visible()

        # Get all traces
        logger.info("Getting trace names")
        try:
            trace_names = traces_page.get_all_trace_names_on_page()
            logger.info(f"Found {len(trace_names)} traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to get trace names.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify attachment for trace
        attachment_name = log_trace_attachment_low_level
        for trace in trace_names:
            logger.info(f"Checking attachments for trace '{trace}'")
            try:
                traces_page.click_first_trace_that_has_name(trace)
                traces_page.check_trace_attachment(attachment_name)
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify attachment for trace.\n"
                    f"Trace name: {trace}\n"
                    f"Expected attachment: {attachment_name}\n"
                    f"Error: {str(e)}"
                ) from e

    @allure.title("Attachments in traces - log_trace_attachment_decorator")
    def test_attachments_decorator(
        self, page, create_project_api, log_trace_attachment_decorator
    ):
        """Test attachment visibility.

        Steps:
        1. Create project via fixture
        2. Create traces and spans with attachment (audio, video, etc) via specified method:
           - Via decorator
        3. Navigate to project traces page
        4. Check attachment for trace
        """
        logger.info("Starting threads creation test")
        project_name = create_project_api

        # Navigate to project
        logger.info(f"Navigating to project '{project_name}'")
        projects_page = ProjectsPage(page)

        try:
            projects_page.go_to_page()
            projects_page.click_project(project_name)
            logger.info("Successfully navigated to project")

        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to project.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Wait for traces to appear on UI
        traces_page = TracesPage(page)
        traces_page.wait_for_traces_to_be_visible()

        # Get all traces
        logger.info("Getting trace names")
        try:
            trace_names = traces_page.get_all_trace_names_on_page()
            logger.info(f"Found {len(trace_names)} traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to get trace names.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify attachment for trace
        attachment_name = log_trace_attachment_decorator
        for trace in trace_names:
            logger.info(f"Checking attachments for trace '{trace}'")
            try:
                traces_page.click_first_trace_that_has_name(trace)
                traces_page.check_trace_attachment(attachment_name)
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify attachment for trace.\n"
                    f"Trace name: {trace}\n"
                    f"Expected attachment: {attachment_name}\n"
                    f"Error: {str(e)}"
                ) from e

    @allure.title("Attachments in traces - log_trace_attachment_in_span")
    def test_attachments_span(
        self, page, create_project_api, log_trace_attachment_in_span
    ):
        """Test attachment visibility.

        Steps:
        1. Create project via fixture
        2. Create span with attachment (audio, video, etc) within a trace:
        3. Navigate to project traces page
        4. Check attachment for trace
        """
        logger.info("Starting threads creation test")
        project_name = create_project_api

        # Navigate to project
        logger.info(f"Navigating to project '{project_name}'")
        projects_page = ProjectsPage(page)

        try:
            projects_page.go_to_page()
            projects_page.click_project(project_name)
            logger.info("Successfully navigated to project")

        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to project.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Wait for traces to appear on UI
        traces_page = TracesPage(page)
        traces_page.wait_for_traces_to_be_visible()

        # Get all traces
        logger.info("Getting trace names")
        try:
            trace_names = traces_page.get_all_trace_names_on_page()
            logger.info(f"Found {len(trace_names)} traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to get trace names.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify attachment for span
        attachment_name, span_name = log_trace_attachment_in_span
        for trace in trace_names:
            logger.info(f"Checking attachments for trace '{trace}'")
            try:
                traces_page.click_first_trace_that_has_name(trace)
                traces_page.check_trace_attachment()

                spans_menu = TracesPageSpansMenu(page)
                spans_menu.open_span_content(span_name)
                spans_menu.check_span_attachment(attachment_name)
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify attachment for span in trace.\n"
                    f"Trace name: {trace}\n"
                    f"Expected attachment: {attachment_name}\n"
                    f"Error: {str(e)}"
                ) from e
