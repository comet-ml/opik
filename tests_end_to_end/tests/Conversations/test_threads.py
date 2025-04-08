import logging
import allure
import pytest

from page_objects.ProjectsPage import ProjectsPage
from page_objects.ThreadsPage import ThreadsPage

logger = logging.getLogger(__name__)


class TestThreadsCrud:
    @pytest.mark.parametrize(
        "threads_fixture", ["log_threads_low_level", "log_threads_with_decorator"]
    )
    @pytest.mark.sanity
    @allure.title("Conversation creation and verification - {threads_fixture}")
    def test_thread_visibility(
        self, page, request, create_project_api, threads_fixture
    ):
        """Test thread visibility.

        Steps:
        1. Create project via fixture
        2. Create threads with spans via specified method (runs twice):
           - Via low-level client
           - Via decorator
        3. Navigate to project threads page
        4. Check content of each logged thread
        """
        logger.info("Starting threads creation test")
        project_name = create_project_api

        thread_configs = request.getfixturevalue(threads_fixture)

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

        # Navigate to threads tab
        threads_page = ThreadsPage(page)
        logger.info(f"Navigating to project threads'{project_name}'")
        try:
            threads_page.switch_to_page()
            logger.info("Successfully navigated to project threads")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to project threads.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Check quantity of threads
        logger.info("Checking quantity of logged threads")
        assert (
            threads_page.get_number_of_threads_on_page() == 3
        ), f"Threads quantity isn't correct, expected to be 3 but was: {threads_page.get_number_of_threads_on_page()}"

        # Check threads content
        for thread in thread_configs:
            thread_id = thread["thread_id"]

            logger.info(f"Checking content for thread '{thread_id}'")
            try:
                threads_page.open_thread_content(thread_id)

                for input, output in zip(thread["inputs"], thread["outputs"]):
                    try:
                        threads_page.check_message_in_thread(input)
                        threads_page.check_message_in_thread(output, True)

                    except Exception as e:
                        raise AssertionError(
                            f"Failed to verify messages exist in the thread.\n"
                            f"Messages: {input}, {output}\n"
                            f"Error: {str(e)}\n"
                            f"Note: This could be due to message not found in thread view"
                        ) from e

                threads_page.close_thread_content()
                logger.info(f"Successfully verified content for thread '{thread_id}'")

            except Exception as e:
                raise AssertionError(
                    f"Failed to verify thread content.\n"
                    f"Thread_id: {thread_id}\n"
                    f"Expected inputs: {thread['inputs']}\n"
                    f"Expected outputs: {thread['outputs']}\n"
                    f"Error: {str(e)}"
                ) from e
            
    @allure.title("Conversation removal test")
    def test_thread_removal(self, page, create_project_api, log_threads_low_level):
        """Test thread removal.

        Steps:
        1. Create project via fixture
        2. Create threads with spans via specified method:
           - Via low-level client
        3. Navigate to project threads page
        4. Remove thread with checkbox from table
        5. Check if thread was removed
        6. Remove thread with content delete button
        7. Check if thread was removed
        """
        logger.info("Starting threads creation test")
        project_name = create_project_api

        thread_configs = log_threads_low_level

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

        # Navigate to threads tab
        threads_page = ThreadsPage(page)
        logger.info(f"Navigating to project threads'{project_name}'")
        try:
            threads_page.switch_to_page()
            logger.info("Successfully navigated to project threads")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to project threads.\n"
                f"Project name: {project_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Check quantity of threads
        logger.info("Checking quantity of logged threads")
        assert (
            threads_page.get_number_of_threads_on_page() == 3
        ), f"Threads quantity isn't correct, expected to be 3 but was: {threads_page.get_number_of_threads_on_page()}"

        # Remove thread with checkbox from table
        thread1_id = thread_configs[0]["thread_id"]
        logger.info("Searching for trace in the table")
        try:
            threads_page.search_for_thread(thread1_id)
            logger.info("Successfully found thread")
        except Exception as e:
            raise AssertionError(
                f"Failed to find thread.\n"
                f"Project name: {project_name}\n"
                f"Thread id: {thread1_id}\n"
                f"Error: {str(e)}"
            ) from e

        logger.info("Removing thread via checkbox")
        try:
            threads_page.delete_thread_from_table()
            logger.info("Successfully deleted thread via checkbox")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete thread.\n"
                f"Project name: {project_name}\n"
                f"Thread id: {thread1_id}\n"
                f"Error: {str(e)}"
            ) from e

        logger.info("Checking thread is not present in the table")
        try:
            threads_page.check_thread_is_deleted(thread1_id)
            logger.info("Thread is deleted and not displayed in the table")
        except Exception as e:
            raise AssertionError(
                f"Thread is present in table after removing.\n"
                f"Project name: {project_name}\n"
                f"Thread id: {thread1_id}\n"
                f"Error: {str(e)}"
            ) from e

        # Remove thread with content delete button
        thread2_id = thread_configs[1]["thread_id"]
        logger.info("Searching for trace in the table")
        try:
            threads_page.search_for_thread(thread2_id)
            logger.info("Successfully found thread")
        except Exception as e:
            raise AssertionError(
                f"Failed to find thread.\n"
                f"Project name: {project_name}\n"
                f"Thread id: {thread2_id}\n"
                f"Error: {str(e)}"
            ) from e

        logger.info("Removing thread via button in thread content bar")
        try:
            threads_page.open_thread_content(thread2_id)
            threads_page.delete_thread_from_thread_content_bar()
            logger.info("Successfully deleted thread via button in thread content bar")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete thread.\n"
                f"Project name: {project_name}\n"
                f"Thread id: {thread2_id}\n"
                f"Error: {str(e)}"
            ) from e

        logger.info("Checking thread is not present in the table")
        try:
            threads_page.check_thread_is_deleted(thread2_id)
            logger.info("Thread is deleted and not displayed in the table")
        except Exception as e:
            raise AssertionError(
                f"Thread is present in table after removing.\n"
                f"Project name: {project_name}\n"
                f"Thread id: {thread2_id}\n"
                f"Error: {str(e)}"
            ) from e
