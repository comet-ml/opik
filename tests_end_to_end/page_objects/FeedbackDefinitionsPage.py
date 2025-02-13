from playwright.sync_api import Page, expect
from typing import Literal, Optional
from .BasePage import BasePage


class FeedbackDefinitionsPage(BasePage):
    def __init__(self, page: Page):
        super().__init__(page, "configuration", "tab=feedback-definitions")
        self.search_bar = self.page.get_by_test_id("search-input")

    def search_feedback_by_name(self, feedback_name: str):
        self.search_bar.click()
        self.search_bar.fill(feedback_name)

    def fill_categorical_values(self, categories):
        if not categories:
            category1_name = self.page.get_by_placeholder("Name").nth(2)
            category1_val = self.page.get_by_placeholder("0.0").first
            category2_name = self.page.get_by_placeholder("Name").nth(3)
            category2_val = self.page.get_by_placeholder("0.0").nth(1)

            category1_name.click()
            category1_name.fill("a")
            category1_val.click()
            category1_val.fill("2")

            category2_name.click()
            category2_name.fill("b")
            category2_val.click()
            category2_val.fill("2")

        else:
            if len(categories.keys()) == 1:
                raise ValueError(
                    "At least 2 categories are required for Categorical feedback definition"
                )
            for i, key in enumerate(categories.keys()):
                self.page.get_by_placeholder("Name").nth(i + 2).click()
                self.page.get_by_placeholder("Name").nth(i + 2).fill(key)
                self.page.get_by_placeholder("0.0").nth(i).click()
                self.page.get_by_placeholder("0.0").nth(i).fill(str(categories[key]))
                self.page.get_by_role("button", name="Add category").click()

    def fill_numerical_values(self, min, max):
        min_box = self.page.get_by_placeholder("Min")
        max_box = self.page.get_by_placeholder("Max")

        both_values_provided = min is not None and max is not None

        min_box.click()
        val = min if both_values_provided else 0
        min_box.fill(str(val))

        max_box.click()
        val = max if both_values_provided else 1
        max_box.fill(str(val))

    def create_new_feedback(
        self,
        feedback_name: str,
        feedback_type: Literal["categorical", "numerical"],
        categories: Optional[dict] = None,
        min: Optional[int] = None,
        max: Optional[int] = None,
    ):
        self.page.get_by_role(
            "button", name="Create new feedback definition"
        ).first.click()
        self.page.get_by_placeholder("Feedback definition name").fill(feedback_name)
        self.page.get_by_role("combobox").click()
        self.page.get_by_label(feedback_type.capitalize()).click()
        if feedback_type == "categorical":
            self.fill_categorical_values(categories=categories)
        else:
            self.fill_numerical_values(min=min, max=max)
        self.page.get_by_role("button", name="Create feedback definition").click()

    def check_feedback_exists_by_name(self, feedback_name: str):
        self.search_feedback_by_name(feedback_name=feedback_name)
        expect(self.page.get_by_text(feedback_name).first).to_be_visible()
        self.search_bar.fill("")

    def check_feedback_not_exists_by_name(self, feedback_name: str):
        self.search_feedback_by_name(feedback_name=feedback_name)
        expect(self.page.get_by_text(feedback_name).first).not_to_be_visible()
        self.search_bar.fill("")

    def delete_feedback_by_name(self, feedback_name: str):
        self.search_feedback_by_name(feedback_name=feedback_name)
        expect(self.page.get_by_role("row", name=feedback_name).first).to_be_visible()
        self.page.get_by_role("row", name=feedback_name).first.get_by_role(
            "button", name="Actions menu"
        ).click()
        self.page.get_by_role("menuitem", name="Delete").click()
        self.page.get_by_role("button", name="Delete feedback definition").click()
        self.search_bar.fill("")

    def edit_feedback_by_name(
        self,
        feedback_name: str,
        new_name: str = "",
        feedback_type: Optional[Literal["categorical", "numerical"]] = None,
        categories: Optional[dict] = None,
        min: Optional[int] = None,
        max: Optional[int] = None,
    ):
        self.search_feedback_by_name(feedback_name=feedback_name)
        self.page.get_by_role("row", name=feedback_name).first.get_by_role(
            "button", name="Actions menu"
        ).click()
        self.page.get_by_role("menuitem", name="Edit").click()

        if new_name:
            self.page.get_by_placeholder("Feedback definition name").fill(new_name)
        ftype = feedback_type or self.page.get_by_role("combobox").inner_text()
        if ftype.lower() == "categorical":
            # currently only supporting resetting the category values entirely, will add entering new categories on top of the old ones later if needed
            self.fill_categorical_values(categories=categories)
        else:
            self.fill_numerical_values(min=min, max=max)

        self.page.get_by_role("button", name="Update feedback definition").click()
        self.search_bar.fill("")

    def get_type_of_feedback_by_name(self, feedback_name: str):
        self.search_feedback_by_name(feedback_name=feedback_name)
        self.page.wait_for_timeout(500)
        return (
            self.page.get_by_role("row").nth(1).get_by_role("cell").nth(2).inner_text()
        )

    def get_values_of_feedback_by_name(self, feedback_name: str):
        self.search_feedback_by_name(feedback_name=feedback_name)
        self.page.wait_for_timeout(500)
        return (
            self.page.get_by_role("row").nth(1).get_by_role("cell").nth(3).inner_text()
        )
