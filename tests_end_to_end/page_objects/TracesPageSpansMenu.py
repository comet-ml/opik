from playwright.sync_api import Page, expect


class TracesPageSpansMenu:
    def __init__(self, page: Page):
        self.page = page
        self.input_output_tab = "Input/Output"
        self.feedback_scores_tab = "Feedback scores"
        self.metadata_tab = "Metadata"

    def get_first_trace_by_name(self, name):
        return self.page.get_by_role("button", name=name).first

    def get_first_span_by_name(self, name):
        return self.page.get_by_role("button", name=name).first

    def check_span_exists_by_name(self, name):
        expect(self.page.get_by_role("button", name=name)).to_be_visible()

    def check_tag_exists_by_name(self, tag_name):
        expect(self.page.get_by_text(tag_name)).to_be_visible()

    def get_input_output_tab(self):
        return self.page.get_by_role("tab", name=self.input_output_tab)

    def get_feedback_scores_tab(self):
        return self.page.get_by_role("tab", name=self.feedback_scores_tab)

    def get_metadata_tab(self):
        return self.page.get_by_role("tab", name="Metadata")
