from playwright.sync_api import Page, expect, Locator
import re
import time


class TracesPage:
    def __init__(self, page: Page):
        self.page = page
        self.traces_table = self.page.get_by_role("table")
        self.trace_names_selector = "tr td:nth-child(3) div span"
        self.trace_id_selector = "tr:nth-child({}) > td:nth-child(2) > div".format
        self.next_page_button_locator = (
            self.page.locator("div")
            .filter(has_text=re.compile(r"^Showing (\d+)-(\d+) of (\d+)"))
            .nth(2)
            .locator("button:nth-of-type(3)")
        )
        self.delete_button_locator = (
            self.page.locator("div")
            .filter(has_text=re.compile(r"^Add to dataset$"))
            .get_by_role("button")
            .nth(2)
        )
        self.attachments_submenu_button = self.page.get_by_role(
            "button", name="Attachments"
        )
        self.attachment_container = self.page.get_by_label("Attachments")

    def get_all_trace_names_on_page(self):
        self.page.wait_for_selector(self.trace_names_selector)
        names = self.page.locator(self.trace_names_selector).all_inner_texts()
        return names

    def click_first_trace_that_has_name(self, trace_name: str):
        self.page.get_by_role("row").filter(has_text=trace_name).first.get_by_role(
            "button"
        ).first.click()

    def check_trace_attachment(self, attachment_name=None):
        if attachment_name:
            expect(self.attachments_submenu_button).to_be_visible()
            expect(
                self.attachment_container.filter(has_text=attachment_name)
            ).to_be_visible()
        else:
            expect(self.attachments_submenu_button).to_have_count(0)

    def get_first_trace_name_on_page(self):
        self.page.wait_for_selector(self.trace_names_selector)
        name = self.page.locator(self.trace_names_selector).first.text_content()
        return name

    def get_all_trace_names_in_project(self):
        names = []
        names.extend(self.get_all_trace_names_on_page())
        while (
            self.next_page_button_locator.is_visible()
            and self.next_page_button_locator.is_enabled()
        ):
            self.next_page_button_locator.click()
            self.page.wait_for_timeout(500)
            names.extend(self.get_all_trace_names_on_page())

        return names

    def get_pagination_button(self) -> Locator:
        return self.page.get_by_role("button", name="Showing")

    def get_number_of_traces_on_page(self):
        try:
            expect(self.page.get_by_role("row").first).to_be_visible()
        except Exception:
            return 0
        finally:
            return self.page.get_by_role("row").count()

    def get_total_number_of_traces_in_project(self):
        pagination_button_text = self.get_pagination_button().inner_text()
        match = re.search(r"of (\d+)", pagination_button_text)
        if match:
            return int(match.group(1))
        else:
            return 0

    def wait_for_traces_to_be_visible(self, timeout=10, initial_delay=1):
        start_time = time.time()
        delay = initial_delay

        while time.time() - start_time < timeout:
            traces_number = self.get_number_of_traces_on_page()
            if traces_number > 0:
                return True

            time.sleep(delay)
            delay = min(delay * 2, timeout - (time.time() - start_time))
            self.page.reload()

        raise TimeoutError(f"could not get traces in UI within {timeout} seconds")

    def delete_single_trace_by_name(self, name: str):
        trace = self.page.get_by_role("row").filter(has_text=name).first
        trace.get_by_label("Select row").click()
        self.delete_button_locator.click()
        self.page.get_by_role("button", name="Delete traces").click()

    def delete_all_traces_that_match_name_contains_filter(self, name: str):
        # TODO compact this into smaller functions
        self.page.get_by_role("button", name="Filters").click()
        filter_row = self.page.get_by_role("row").filter(
            has=self.page.get_by_role("cell", name="Where")
        )
        filter_row.get_by_role("cell").filter(has_text=re.compile(r"^Column$")).click()
        self.page.get_by_label("Name").click()
        self.page.get_by_test_id("filter-string-input").click()
        self.page.get_by_test_id("filter-string-input").fill(name)
        expect(self.page.get_by_role("button", name="Filters (1)")).to_be_visible()
        self.page.keyboard.press(key="Escape")

        total_traces = self.get_total_number_of_traces_in_project()
        while total_traces > 0:
            expect(self.page.get_by_label("Select all")).to_be_visible()
            self.page.get_by_label("Select all").click()
            self.delete_button_locator.click()
            self.page.get_by_role("button", name="Delete traces").click()

            pagination_button = self.get_pagination_button()
            expect(pagination_button).not_to_have_text(
                f"Showing 1-10 of {total_traces}"
            )
            total_traces = self.get_total_number_of_traces_in_project()

    def add_all_traces_to_new_dataset(self, dataset_name: str):
        self.page.get_by_label("Select all").click()
        self.page.get_by_role("button", name="Add to dataset").click()
        self.page.get_by_role("button", name="Create new dataset").click()
        self.page.get_by_placeholder("Dataset name").fill(dataset_name)
        self.page.get_by_role("button", name="Create dataset").click()
