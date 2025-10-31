import pytest
import re
from playwright.sync_api import Page
from page_objects.FeedbackDefinitionsPage import FeedbackDefinitionsPage
from collections import Counter
import logging
import allure

logger = logging.getLogger(__name__)


class TestFeedbacksCrud:
    @allure.title("Basic feedback definition creation")
    @pytest.mark.regression
    def test_feedback_definition_visibility(
        self,
        page: Page,
        create_feedback_definition_categorical_ui,
        create_feedback_definition_numerical_ui,
    ):
        """Test visibility of categorical and numerical feedback definitions in UI.

        Steps:
        1. Create two feedback definitions via fixtures:
           - One categorical definition
           - One numerical definition
        2. Navigate to feedback definitions page
        3. Verify both definitions appear in the table
        """
        logger.info("Starting feedback definitions visibility test")
        cat_name = create_feedback_definition_categorical_ui["name"]
        num_name = create_feedback_definition_numerical_ui["name"]

        # Navigate to page
        logger.info("Navigating to feedback definitions page")
        feedbacks_page = FeedbackDefinitionsPage(page)
        try:
            feedbacks_page.go_to_page()
            logger.info("Successfully navigated to feedback definitions page")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to feedback definitions page.\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to page load issues"
            ) from e

        # Verify categorical definition
        logger.info(f"Checking categorical definition '{cat_name}'")
        try:
            feedbacks_page.check_feedback_exists_by_name(cat_name)
            logger.info("Successfully verified categorical definition")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify categorical definition.\n"
                f"Definition name: {cat_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to definition not found in table"
            ) from e

        # Verify numerical definition
        logger.info(f"Checking numerical definition '{num_name}'")
        try:
            feedbacks_page.check_feedback_exists_by_name(num_name)
            logger.info("Successfully verified numerical definition")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify numerical definition.\n"
                f"Definition name: {num_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to definition not found in table"
            ) from e

    @pytest.mark.regression
    @allure.title("Feedback definition edit")
    def test_feedback_definition_edit(
        self,
        page: Page,
        create_feedback_definition_categorical_ui,
        create_feedback_definition_numerical_ui,
    ):
        """Test editing of categorical and numerical feedback definitions.

        Steps:
        1. Create two feedback definitions via fixtures
        2. Navigate to feedback definitions page
        3. Edit categorical definition name
        4. Edit numerical definition name
        5. Verify:
           - Old names no longer appear in table
           - New names appear in table
           - Types remain correct (categorical/numerical)
           - Definition values are updated
        """
        logger.info("Starting feedback definitions edit test")
        cat_name = create_feedback_definition_categorical_ui["name"]
        num_name = create_feedback_definition_numerical_ui["name"]
        cat_new_name = f"{cat_name}_edited"
        num_new_name = f"{num_name}_edited"

        # Navigate to page
        logger.info("Navigating to feedback definitions page")
        feedbacks_page = FeedbackDefinitionsPage(page)
        try:
            feedbacks_page.go_to_page()
            logger.info("Successfully navigated to feedback definitions page")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to feedback definitions page.\n" f"Error: {str(e)}"
            ) from e

        # Edit categorical definition
        logger.info(f"Editing categorical definition '{cat_name}' to '{cat_new_name}'")
        try:
            feedbacks_page.edit_feedback_by_name(
                feedback_name=cat_name,
                new_name=cat_new_name,
                categories={"test1": 1, "test2": 2},
            )
            logger.info("Successfully edited categorical definition")
        except Exception as e:
            raise AssertionError(
                f"Failed to edit categorical definition.\n"
                f"Original name: {cat_name}\n"
                f"New name: {cat_new_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to edit dialog issues"
            ) from e

        # Edit numerical definition
        logger.info(f"Editing numerical definition '{num_name}' to '{num_new_name}'")
        try:
            feedbacks_page.edit_feedback_by_name(
                feedback_name=num_name, new_name=num_new_name, min=5, max=10
            )
            logger.info("Successfully edited numerical definition")
        except Exception as e:
            raise AssertionError(
                f"Failed to edit numerical definition.\n"
                f"Original name: {num_name}\n"
                f"New name: {num_new_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to edit dialog issues"
            ) from e

        # Verify new names and types
        logger.info("Verifying new names and types")
        try:
            feedbacks_page.check_feedback_exists_by_name(cat_new_name)
            feedbacks_page.check_feedback_exists_by_name(num_new_name)

            assert (
                feedbacks_page.get_type_of_feedback_by_name(cat_new_name)
                == "Categorical"
            )
            assert (
                feedbacks_page.get_type_of_feedback_by_name(num_new_name) == "Numerical"
            )
            logger.info("Successfully verified new names and types")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify new definitions.\n"
                f"Expected names: {cat_new_name}, {num_new_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify categorical values
        logger.info("Verifying categorical definition values")
        try:
            categories_ui_values = feedbacks_page.get_values_of_feedback_by_name(
                cat_new_name
            )
            categories = re.findall(r"\b\w+\b", categories_ui_values)
            assert Counter(categories) == Counter(["test1", "test2"])
            logger.info("Successfully verified categorical values")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify categorical values.\n"
                f"Definition name: {cat_new_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify numerical values
        logger.info("Verifying numerical definition values")
        try:
            numerical_ui_values = feedbacks_page.get_values_of_feedback_by_name(
                num_new_name
            )
            match = re.search(r"Min: (\d+), Max: (\d+)", numerical_ui_values)
            assert match is not None, "Improper formatting of min-max values"
            assert int(match.group(1)) == 5
            assert int(match.group(2)) == 10
            logger.info("Successfully verified numerical values")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify numerical values.\n"
                f"Definition name: {num_new_name}\n"
                f"Expected: Min: 5, Max: 10\n"
                f"Got: {numerical_ui_values}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.regression
    @allure.title("Feedback definition deletion")
    def test_feedback_definition_deletion(
        self,
        page: Page,
        create_feedback_definition_categorical_ui,
        create_feedback_definition_numerical_ui,
    ):
        """Test deletion of categorical and numerical feedback definitions.

        Steps:
        1. Create two feedback definitions via fixtures
        2. Navigate to feedback definitions page
        3. Delete categorical definition
        4. Verify categorical definition removed
        5. Delete numerical definition
        6. Verify numerical definition removed
        """
        logger.info("Starting feedback definitions deletion test")
        cat_name = create_feedback_definition_categorical_ui["name"]
        num_name = create_feedback_definition_numerical_ui["name"]

        # Navigate to page
        logger.info("Navigating to feedback definitions page")
        feedbacks_page = FeedbackDefinitionsPage(page)
        try:
            feedbacks_page.go_to_page()
            logger.info("Successfully navigated to feedback definitions page")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to feedback definitions page.\n" f"Error: {str(e)}"
            ) from e

        # Delete categorical definition
        logger.info(f"Deleting categorical definition '{cat_name}'")
        try:
            feedbacks_page.delete_feedback_by_name(feedback_name=cat_name)
            logger.info("Successfully deleted categorical definition")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete categorical definition.\n"
                f"Definition name: {cat_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to delete button not found or dialog issues"
            ) from e

        # Verify categorical deletion
        logger.info("Verifying categorical definition removed")
        try:
            feedbacks_page.check_feedback_not_exists_by_name(feedback_name=cat_name)
            logger.info("Successfully verified categorical definition removed")
        except Exception as e:
            raise AssertionError(
                f"Categorical definition still visible after deletion.\n"
                f"Definition name: {cat_name}\n"
                f"Error: {str(e)}"
            ) from e

        # Delete numerical definition
        logger.info(f"Deleting numerical definition '{num_name}'")
        try:
            feedbacks_page.delete_feedback_by_name(feedback_name=num_name)
            logger.info("Successfully deleted numerical definition")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete numerical definition.\n"
                f"Definition name: {num_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to delete button not found or dialog issues"
            ) from e

        # Verify numerical deletion
        logger.info("Verifying numerical definition removed")
        try:
            feedbacks_page.check_feedback_not_exists_by_name(feedback_name=num_name)
            logger.info("Successfully verified numerical definition removed")
        except Exception as e:
            raise AssertionError(
                f"Numerical definition still visible after deletion.\n"
                f"Definition name: {num_name}\n"
                f"Error: {str(e)}"
            ) from e
