from playwright.sync_api import Page
import re


class DatasetItemsPage:
    def __init__(self, page: Page):
        self.page = page
        self.next_page_button_locator = (
            self.page.locator("div")
            .filter(has_text=re.compile(r"^Showing (\d+)-(\d+) of (\d+)"))
            .nth(2)
            .locator("button:nth-of-type(3)")
        )

    def remove_default_columns(self):
        self.page.get_by_role("button", name="Columns").click()
        created_toggle = self.page.get_by_role(
            "button", name="Created", exact=True
        ).get_by_role("checkbox")
        if created_toggle.is_checked():
            created_toggle.click()

        last_updated_toggle = self.page.get_by_role(
            "button", name="Last updated"
        ).get_by_role("checkbox")
        if last_updated_toggle.is_checked():
            last_updated_toggle.click()

        created_by_toggle = self.page.get_by_role(
            "button", name="Created by", exact=True
        ).get_by_role("checkbox")
        if created_by_toggle.is_checked():
            created_by_toggle.click()

        self.page.keyboard.press("Escape")

    def delete_first_item_on_page_and_return_content(self):
        self.remove_default_columns()
        keys: list[str] = self.page.locator("th").all_inner_texts()[1:-1]
        item = {}

        row = self.page.locator("tr").nth(1)
        cells = row.locator("td").all()
        for cell_index, cell in enumerate(cells[1:-1]):
            content = ""
            if cell_index == 0:
                cell.get_by_role("button").hover()
                row.get_by_role("button").nth(1).click()
                content = self.page.evaluate("navigator.clipboard.readText()")
            else:
                content = cell.text_content()
            item[keys[cell_index]] = content

        row.get_by_role("button", name="Actions menu").click()
        self.page.get_by_role("menuitem", name="Delete").click()
        self.page.get_by_role("button", name="Delete dataset item").click()

        return item

    def insert_dataset_item(self, item: str):
        self.page.get_by_role("button", name="Create dataset item").click()
        textbox = self.page.get_by_role("textbox")
        textbox.focus()
        self.page.keyboard.press("Meta+A")
        self.page.keyboard.press("Backspace")
        textbox.fill(item)
        self.page.get_by_role("button", name="Create dataset item").click()

    def get_all_dataset_items_on_current_page(self):
        self.remove_default_columns()

        keys: list[str] = self.page.locator("th").all_inner_texts()[1:-1]
        items = []

        rows = self.page.locator("tr").all()
        for row_index, row in enumerate(rows[1:]):
            item = {}
            cells = row.locator("td").all()
            for cell_index, cell in enumerate(cells[1:-1]):
                content = ""
                if cell_index == 0:
                    cell.get_by_role("button").hover()
                    row.get_by_role("button").nth(1).click()
                    content = self.page.evaluate("navigator.clipboard.readText()")
                else:
                    content = cell.text_content()
                item[keys[cell_index]] = content
            items.append(item)

        return items

    def get_all_items_in_dataset(self):
        items = []
        items.extend(self.get_all_dataset_items_on_current_page())
        while (
            self.next_page_button_locator.is_visible()
            and self.next_page_button_locator.is_enabled()
        ):
            self.next_page_button_locator.click()
            self.page.wait_for_timeout(500)
            items.extend(self.get_all_dataset_items_on_current_page())

        return items

    def search_dataset_items(self, search_term: str):
        """Search for dataset items using the search input."""
        search_input = self.page.get_by_placeholder("Search")
        search_input.clear()
        search_input.fill(search_term)
        # Wait for search results to load
        self.page.wait_for_timeout(500)

    def clear_search(self):
        """Clear the search input."""
        search_input = self.page.get_by_placeholder("Search")
        search_input.clear()
        # Wait for results to refresh
        self.page.wait_for_timeout(500)

    def get_search_results_count(self):
        """Get the number of items currently visible after search."""
        try:
            # Look for the pagination text to get total count
            pagination_text = self.page.locator("div").filter(has_text=re.compile(r"^Showing (\d+)-(\d+) of (\d+)")).text_content()
            if pagination_text:
                # Extract total count from "Showing 1-5 of 10" format
                match = re.search(r"of (\d+)", pagination_text)
                if match:
                    return int(match.group(1))
            # If no pagination text, count visible rows
            rows = self.page.locator("tbody tr").count()
            return rows
        except:
            # Fallback: count visible rows
            return self.page.locator("tbody tr").count()

    def verify_search_results_contain_term(self, search_term: str):
        """Verify that all visible items contain the search term."""
        self.remove_default_columns()
        
        rows = self.page.locator("tbody tr").all()
        if not rows:
            return False
            
        for row in rows:
            cells = row.locator("td").all()
            row_text = ""
            # Concatenate all cell text content
            for cell in cells[1:-1]:  # Skip first and last columns (checkbox and actions)
                cell_content = cell.text_content() or ""
                row_text += cell_content.lower() + " "
            
            # Check if search term is found in row text
            if search_term.lower() not in row_text:
                return False
                
        return True
