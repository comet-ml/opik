from playwright.sync_api import Page, expect, Locator
import re

class TracesPage:
    def __init__(self, page: Page):
        self.page = page
        self.traces_table = self.page.get_by_role('table')
        self.trace_names_selector = 'tr td:nth-child(2) div span'
        self.next_page_button_locator = self.page.locator("div:has(> button:nth-of-type(4))").locator('button:nth-of-type(3)')
        self.delete_button_locator = self.page.locator("div").filter(has_text=re.compile(r"^Add to dataset$")).get_by_role("button").nth(2)



    def get_all_trace_names_on_page(self):
        self.page.wait_for_selector(self.trace_names_selector)
        names = self.page.locator(self.trace_names_selector).all_inner_texts()
        return names
    

    def get_first_trace_name_on_page(self):
        self.page.wait_for_selector(self.trace_names_selector)
        name = self.page.locator(self.trace_names_selector).first.text_content()
        return name
    

    def get_all_trace_names_in_project(self):
        names = []
        names.extend(self.get_all_trace_names_on_page())
        while self.next_page_button_locator.is_visible() and self.next_page_button_locator.is_enabled():
            self.next_page_button_locator.click()
            self.page.wait_for_timeout(500)
            names.extend(self.get_all_trace_names_on_page())

        return names
    

    def get_pagination_button(self) -> Locator:
        return self.page.get_by_role('button', name='Showing')
    

    def get_number_of_traces_on_page(self):
        try:
            expect(self.page.get_by_role('row').first).to_be_visible()
        except Exception as e:
            return 0
        finally:
            return self.page.get_by_role('row').count()
        

    def get_total_number_of_traces_in_project(self):
        pagination_button_text = self.get_pagination_button().inner_text()
        match = re.search(r'of (\d+)', pagination_button_text)
        if match:
            return int(match.group(1))
        else:
            return 0

        
    def delete_single_trace_by_name(self, name: str):
        trace = self.page.get_by_role('row').filter(has_text=name).first
        trace.get_by_label('Select row').click()
        self.delete_button_locator.click()
        self.page.get_by_role('button', name='Delete traces').click()


    def delete_all_traces_that_match_name_contains_filter(self, name: str):
        #TODO compact this into smaller functions
        self.page.get_by_role("button", name="Filters").click()
        filter_row = self.page.get_by_role('row').filter(has=self.page.get_by_role('cell', name='Where'))
        filter_row.get_by_role('cell').filter(has_text=re.compile(r"^Column$")).click()
        self.page.get_by_label("Name").click()
        self.page.get_by_test_id("filter-string-input").click()
        self.page.get_by_test_id("filter-string-input").fill(name)
        expect(self.page.get_by_role('button', name='Filters (1)')).to_be_visible()
        self.page.keyboard.press(key="Escape")

        total_traces = self.get_total_number_of_traces_in_project()
        while total_traces > 0:
            expect(self.page.get_by_label('Select all')).to_be_visible()
            self.page.get_by_label("Select all").click()
            self.delete_button_locator.click()
            self.page.get_by_role("button", name="Delete traces").click()
            
            pagination_button = self.get_pagination_button()
            expect(pagination_button).not_to_have_text(f'Showing 1-10 of {total_traces}')
            total_traces = self.get_total_number_of_traces_in_project()