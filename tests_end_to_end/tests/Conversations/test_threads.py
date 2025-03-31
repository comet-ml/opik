import logging
import allure
import pytest

from page_objects.ProjectsPage import ProjectsPage
from page_objects.ThreadsPage import ThreadsPage

logger = logging.getLogger(__name__)

class TestThreadsCrud:
    @pytest.mark.parametrize(
        "threads_fixture",
        ["log_threads_low_level", 
         "log_threads_with_decorator"],
    )
    @pytest.mark.sanity
    @allure.title("Conversation creation and verification - {threads_fixture}")
    def test_thread_visibility(self, page, request, create_project_api, threads_fixture):
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

        # Navigate to project threads
        logger.info(f"Navigating to project '{project_name}'")
        projects_page = ProjectsPage(page)
        threads_page = ThreadsPage(page)
        try:
            projects_page.go_to_page()
            projects_page.click_project(project_name)
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
            conversation = []

            for input, output in zip(thread["inputs"], thread["outputs"]):
                conversation.append(input)
                conversation.append(output)

            logger.info(f"Checking content for thread '{thread_id}'")
            try:
                threads_page.open_thread_content(thread_id)

                for message in conversation:
                    try:
                        threads_page.check_message_in_thread(message, conversation.index(message))
                    
                    except Exception as e:
                        raise AssertionError(
                            f"Failed to verify message exists in the thread.\n"
                            f"Message name: {message}\n"
                            f"Message order: {conversation.index(message) + 1}\n"
                            f"Error: {str(e)}\n"
                            f"Note: This could be due to message not found in thread view"
                        ) from e
                
                threads_page.close_thread_content()

            except Exception as e:
                raise AssertionError(
                    f"Failed to verify thread content.\n"
                    f"Thread_id: {thread_id}\n"
                    f"Expected inputs: {thread['inputs']}\n"
                    f"Expected outputs: {thread['outputs']}\n"
                    f"Error: {str(e)}"
                ) from e