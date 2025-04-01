import time
from playwright.sync_api import Page,expect

class ThreadsPage:
    def __init__(self, page: Page):
        self.page = page
        self.threads_tab = self.page.get_by_role("tab", name="Threads")
        self.thread_row = self.page.locator("tbody tr")
        self.thread_container = self.page.get_by_test_id("thread")
        self.output_container = self.page.get_by_test_id("thread").locator('.comet-markdown')
    
    def switch_to_page(self):
        self.threads_tab.click()
    
    def get_number_of_threads_on_page(self):
        try:
            for i in range(5):
                if(not self.thread_row.count()):
                    time.sleep(1)
                    self.page.reload()
            expect(self.thread_row.first).to_be_visible()
        except Exception:
            return 0
        finally:
            return self.thread_row.count()

    def open_thread_content(self, thread_id):
        self.page.get_by_role("button", name=thread_id).click()
    
    def check_message_in_thread(self, message, output = False):
        if output:
            expect(self.output_container.filter(has_text=message)).to_be_visible()
        else:
            expect(self.thread_container.get_by_text(message)).to_be_visible()

    def close_thread_content(self):
        self.page.keyboard.press('Escape')

