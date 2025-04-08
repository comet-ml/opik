import time
from playwright.sync_api import Page, expect


class ThreadsPage:
    def __init__(self, page: Page):
        self.page = page
        self.search_input = self.page.get_by_test_id("search-input")
        self.threads_tab = self.page.get_by_role("tab", name="Threads")
        self.thread_row = self.page.locator("tbody tr")
        self.thread_container = self.page.get_by_test_id("thread")
        self.thread_container_delete_button = self.page.get_by_role(
            "button", name="Delete"
        )
        self.thread_delete_popup_delete_button = self.page.get_by_role("button").filter(
            has_text="Delete thread"
        )
        self.thread_checkbox = self.page.get_by_label("Select row")
        self.thread_table_delete_button = (
            self.page.get_by_role("tabpanel")
            .locator("div")
            .filter(has_text="Columns")
            .get_by_role("button")
            .nth(3)
        )
        self.output_container = self.page.get_by_test_id("thread").locator(
            ".comet-markdown"
        )

    def switch_to_page(self):
        self.threads_tab.click()

    def get_number_of_threads_on_page(self):
        try:
            for i in range(5):
                if not self.thread_row.count():
                    time.sleep(1)
                    self.page.reload()
            expect(self.thread_row.first).to_be_visible()
        except Exception as e:
            raise AssertionError(
                f"No threads found in the project.\n"
                f"Error: {str(e)}"
            ) from e
        finally:
            return self.thread_row.count()

    def open_thread_content(self, thread_id):
        self.page.get_by_role("button", name=thread_id).click()

    def check_message_in_thread(self, message, output=False):
        if output:
            expect(self.output_container.filter(has_text=message)).to_be_visible()
        else:
            expect(self.thread_container.get_by_text(message)).to_be_visible()

    def search_for_thread(self, thread_id):
        self.search_input.fill(thread_id)
        expect(self.thread_row).to_have_count(1)

    def delete_thread_from_table(self):
        self.thread_checkbox.click()
        self.thread_table_delete_button.click()
        self.thread_delete_popup_delete_button.click()

    def check_thread_is_deleted(self, thread_id):
        self.search_input.fill(thread_id)
        expect(self.thread_row.filter(has_text=thread_id)).to_have_count(0)

    def delete_thread_from_thread_content_bar(self):
        self.thread_container_delete_button.click()
        self.thread_delete_popup_delete_button.click()

    def close_thread_content(self):
        self.page.keyboard.press("Escape")
