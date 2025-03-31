from playwright.sync_api import Page,expect

class ThreadsPage:
    def __init__(self, page: Page):
        self.page = page
        self.threads_tab = self.page.get_by_role("tab", name="Threads")
        self.thread_row = self.page.locator("tbody tr")
        self.message_in_thread = self.page.get_by_test_id("thread").locator("div.comet-markdown")
    
    def switch_to_page(self):
        self.threads_tab.click()
        expect(self.page.get_by_test_id("search-input")).to_be_visible()
    
    def get_number_of_threads_on_page(self):
        try:
            expect(self.thread_row.first).to_be_visible()
        except Exception:
            return 0
        finally:
            return self.thread_row.count()

    def open_thread_content(self, thread_id):
        self.page.get_by_role("button", name=thread_id).click()
    
    def check_message_in_thread(self, message, index):
        expect(self.message_in_thread.nth(index).filter(has_text=message)).to_be_visible()

    def close_thread_content(self):
        self.page.keyboard.press('Escape')

