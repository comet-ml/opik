from playwright.sync_api import Page, expect


class TracesPageSpansMenu:
    def __init__(self, page: Page):
        self.page = page
        self.input_output_tab = "Input/Output"
        self.feedback_scores_tab = "Feedback scores"
        self.metadata_tab = "Metadata"
        self.span_title = self.page.get_by_test_id("data-viewer-title")
        self.attachments_submenu_button = self.page.get_by_role(
            "button", name="Attachments"
        )
        self.attachment_container = self.page.get_by_label("Attachments")

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

    def open_span_content(self, span_name):
        self.page.get_by_role("button", name=span_name).click()
        expect(self.span_title.filter(has_text=span_name)).to_be_visible()

    def check_span_attachment(self, attachment_name):
        expect(self.attachments_submenu_button).to_be_visible()
        expect(
            self.attachment_container.filter(has_text=attachment_name)
        ).to_be_visible()
