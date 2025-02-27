import pytest
from playwright.sync_api import expect
from page_objects.TracesPageSpansMenu import TracesPageSpansMenu
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
import logging
import allure

logger = logging.getLogger(__name__)


class TestTraceSpans:
    @pytest.mark.parametrize(
        "traces_fixture",
        ["log_traces_with_spans_low_level", "log_traces_with_spans_decorator"],
    )
    @pytest.mark.sanity
    @allure.id("T4")
    @allure.title("Span creation and verification - {traces_fixture}")
    def test_spans_of_traces(self, page, request, create_project_api, traces_fixture):
        """Test span presence in traces.

        Steps:
        1. Create project via fixture
        2. Create traces with spans via specified method (runs twice):
           - Via low-level client
           - Via decorator
        3. Navigate to project traces page
        4. For each trace:
           - Open trace details
           - Verify all expected spans present
           - Verify span names match configuration
        """
        logger.info("Starting spans of traces test")
        project_name = create_project_api
        _, span_config = request.getfixturevalue(traces_fixture)

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

        # Get all traces
        logger.info("Getting trace names")
        traces_page = TracesPage(page)
        try:
            trace_names = traces_page.get_all_trace_names_on_page()
            logger.info(f"Found {len(trace_names)} traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to get trace names.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify spans for each trace
        for trace in trace_names:
            logger.info(f"Checking spans for trace '{trace}'")
            try:
                traces_page.click_first_trace_that_has_name(trace)
                spans_menu = TracesPageSpansMenu(page)

                # Verify each expected span
                for count in range(span_config["count"]):
                    prefix = span_config["prefix"]
                    span_name = f"{prefix}{count}"
                    logger.info(f"Verifying span '{span_name}'")
                    try:
                        spans_menu.check_span_exists_by_name(span_name)
                        logger.info(f"Successfully verified span '{span_name}' exists")
                    except Exception as e:
                        raise AssertionError(
                            f"Failed to verify span exists.\n"
                            f"Span name: {span_name}\n"
                            f"Error: {str(e)}\n"
                            f"Note: This could be due to span not found in trace view"
                        ) from e

                try:
                    spans_menu.page.keyboard.press("Escape")
                    logger.info(f"Successfully verified all spans for trace '{trace}'")
                except Exception as e:
                    raise AssertionError(
                        f"Failed to close spans menu.\n"
                        f"Trace name: {trace}\n"
                        f"Error: {str(e)}"
                    ) from e
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify spans for trace.\n"
                    f"Trace name: {trace}\n"
                    f"Expected spans: {span_config['prefix']}[0-{span_config['count']-1}]\n"
                    f"Error: {str(e)}"
                ) from e

    @pytest.mark.parametrize(
        "traces_fixture",
        ["log_traces_with_spans_low_level", "log_traces_with_spans_decorator"],
    )
    @pytest.mark.sanity
    @allure.id("T5")
    @allure.title("Span details and metadata - {traces_fixture}")
    def test_trace_and_span_details(
        self, page, request, create_project_api, traces_fixture
    ):
        """Test span details and metadata in traces.

        Steps:
        1. Create project via fixture
        2. Create traces with spans via specified method (runs twice):
           - Via low-level client
           - Via decorator
        3. Navigate to project traces page
        4. For each trace:
           - Open trace details
           - For each span:
             * Verify feedback scores match configuration
             * Verify metadata matches configuration
        """
        logger.info("Starting trace and span details test")
        project_name = create_project_api
        _, span_config = request.getfixturevalue(traces_fixture)

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

        # Get all traces
        logger.info("Getting trace names")
        traces_page = TracesPage(page)
        try:
            trace_names = traces_page.get_all_trace_names_on_page()
            logger.info(f"Found {len(trace_names)} traces")
        except Exception as e:
            raise AssertionError(
                f"Failed to get trace names.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify details for each trace
        for trace in trace_names:
            logger.info(f"Checking details for trace '{trace}'")
            try:
                traces_page.click_first_trace_that_has_name(trace)
                spans_menu = TracesPageSpansMenu(page)

                # Check each span's details
                for count in range(span_config["count"]):
                    prefix = span_config["prefix"]
                    span_name = f"{prefix}{count}"
                    logger.info(f"Verifying details for span '{span_name}'")

                    # Select span
                    spans_menu.get_first_span_by_name(span_name).click()

                    # Verify feedback scores
                    logger.info("Checking feedback scores")
                    try:
                        spans_menu.get_feedback_scores_tab().click()
                        for score in span_config["feedback_scores"]:
                            expect(
                                page.get_by_role("cell", name=score["name"], exact=True)
                            ).to_be_visible()
                            expect(
                                page.get_by_role(
                                    "cell",
                                    name=str(score["value"]),
                                    exact=True,
                                )
                            ).to_be_visible()
                        logger.info("Successfully verified feedback scores")
                    except Exception as e:
                        raise AssertionError(
                            f"Failed to verify feedback scores for span.\n"
                            f"Span name: {span_name}\n"
                            f"Expected scores: {span_config['feedback_scores']}\n"
                            f"Error: {str(e)}"
                        ) from e

                    # Verify metadata
                    logger.info("Checking metadata")
                    try:
                        spans_menu.get_metadata_tab().click()
                        for md_key in span_config["metadata"]:
                            expect(
                                page.get_by_text(
                                    f"{md_key}: {span_config['metadata'][md_key]}"
                                )
                            ).to_be_visible()
                        logger.info("Successfully verified metadata")
                    except Exception as e:
                        raise AssertionError(
                            f"Failed to verify metadata for span.\n"
                            f"Span name: {span_name}\n"
                            f"Expected metadata: {span_config['metadata']}\n"
                            f"Error: {str(e)}"
                        ) from e

                    # Wait for stability
                    page.wait_for_timeout(500)
                    logger.info(f"Successfully verified details for span '{span_name}'")

                spans_menu.page.keyboard.press("Escape")
                logger.info(f"Successfully verified all details for trace '{trace}'")
            except Exception as e:
                raise AssertionError(
                    f"Failed to verify trace details.\n"
                    f"Trace name: {trace}\n"
                    f"Error: {str(e)}"
                ) from e
