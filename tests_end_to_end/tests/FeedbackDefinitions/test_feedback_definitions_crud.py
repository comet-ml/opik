import re
from playwright.sync_api import Page
from page_objects.FeedbackDefinitionsPage import FeedbackDefinitionsPage
from collections import Counter


class TestFeedbacksCrud:
    def test_feedback_definition_visibility(
        self,
        page: Page,
        create_feedback_definition_categorical_ui,
        create_feedback_definition_numerical_ui,
    ):
        """
        Creates a categorical and numerical feedback definition and checks they are properly displayed in the UI
        1. Create 2 feedback definitions (categorical and numerical)
        2. Check the feedback definitions appear in the table
        """
        feedbacks_page = FeedbackDefinitionsPage(page)
        feedbacks_page.go_to_page()
        feedbacks_page.check_feedback_exists_by_name(
            create_feedback_definition_categorical_ui["name"]
        )
        feedbacks_page.check_feedback_exists_by_name(
            create_feedback_definition_numerical_ui["name"]
        )

    def test_feedback_definition_edit(
        self,
        page: Page,
        create_feedback_definition_categorical_ui,
        create_feedback_definition_numerical_ui,
    ):
        """
        Tests that updating the data of feedback definition correctly displays in the UI
        1. Create 2 feedback definitions (categorical and numerical)
        2. Update the name of the 2 feedbacks
        3. Update the values of the 2 feedbacks (change the categories and the min-max values, respectively)
        4. Check that the new names are properly displayed in the table
        5. Check that the new values are properly displayed in the table
        """
        feedbacks_page = FeedbackDefinitionsPage(page)
        feedbacks_page.go_to_page()

        fd_cat_name = create_feedback_definition_categorical_ui["name"]
        fd_num_name = create_feedback_definition_numerical_ui["name"]

        new_categories = {"test1": 1, "test2": 2, "test3": 3}
        new_min = 5
        new_max = 10
        cat_new_name = "updated_name_categorical"
        num_new_name = "updated_name_numerical"

        feedbacks_page.edit_feedback_by_name(
            feedback_name=fd_cat_name, new_name=cat_new_name, categories=new_categories
        )
        create_feedback_definition_categorical_ui["name"] = cat_new_name

        feedbacks_page.edit_feedback_by_name(
            feedback_name=fd_num_name, new_name=num_new_name, min=new_min, max=new_max
        )
        create_feedback_definition_numerical_ui["name"] = num_new_name

        feedbacks_page.check_feedback_exists_by_name(cat_new_name)
        feedbacks_page.check_feedback_exists_by_name(num_new_name)

        assert (
            feedbacks_page.get_type_of_feedback_by_name(cat_new_name) == "Categorical"
        )
        assert feedbacks_page.get_type_of_feedback_by_name(num_new_name) == "Numerical"

        categories_ui_values = feedbacks_page.get_values_of_feedback_by_name(
            cat_new_name
        )
        categories = re.findall(r"\b\w+\b", categories_ui_values)
        assert Counter(categories) == Counter(new_categories.keys())

        numerical_ui_values = feedbacks_page.get_values_of_feedback_by_name(
            num_new_name
        )
        match = re.search(r"Min: (\d+), Max: (\d+)", numerical_ui_values)
        assert match is not None, "Improper formatting of min-max values"
        min_value = match.group(1)
        max_value = match.group(2)
        assert int(min_value) == new_min
        assert int(max_value) == new_max

    def test_feedback_definition_deletion(
        self,
        page: Page,
        create_feedback_definition_categorical_ui,
        create_feedback_definition_numerical_ui,
    ):
        """
        Checks that deleting feedback definitions properly removes them from the table
        1. Create 2 feedback definitions (categorical and numerical)
        2. Delete them
        3. Check that they no longer appear in the table
        """
        feedbacks_page = FeedbackDefinitionsPage(page)
        feedbacks_page.go_to_page()

        fd_cat_name = create_feedback_definition_categorical_ui["name"]
        fd_num_name = create_feedback_definition_numerical_ui["name"]

        feedbacks_page.delete_feedback_by_name(feedback_name=fd_cat_name)
        feedbacks_page.delete_feedback_by_name(feedback_name=fd_num_name)

        feedbacks_page.check_feedback_not_exists_by_name(feedback_name=fd_cat_name)
        feedbacks_page.check_feedback_not_exists_by_name(feedback_name=fd_num_name)
