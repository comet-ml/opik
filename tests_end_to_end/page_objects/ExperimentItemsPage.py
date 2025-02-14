from playwright.sync_api import Page, Locator
import re


class ExperimentItemsPage:
    def __init__(self, page: Page):
        self.page = page
        self.next_page_button_locator = (
            self.page.locator("div")
            .filter(has_text=re.compile(r"^Showing (\d+)-(\d+) of (\d+)"))
            .nth(2)
            .locator("button:nth-of-type(3)")
        )

    def get_pagination_button(self) -> Locator:
        return self.page.get_by_role("button", name="Showing")

    def get_total_number_of_items_in_experiment(self):
        pagination_button_text = self.get_pagination_button().inner_text()
        match = re.search(r"of (\d+)", pagination_button_text)
        if match:
            return int(match.group(1))
        else:
            return 0

    def get_id_of_nth_experiment_item(self, n: int):
        row = self.page.locator("tr").nth(n + 1)
        cell = row.locator("td").nth(1)
        cell.hover()
        cell.get_by_role("button").nth(1).click()
        id = self.page.evaluate("navigator.clipboard.readText()")
        return id

    def get_all_item_ids_on_current_page(self):
        ids = []
        rows = self.page.locator("tr").all()
        for row_index, row in enumerate(rows[2:]):
            cell = row.locator("td").nth(1)
            cell.hover()
            cell.get_by_role("button").nth(1).click()
            id = self.page.evaluate("navigator.clipboard.readText()")
            ids.append(id)

        return ids

    def get_all_item_ids_in_experiment(self):
        ids = []
        ids.extend(self.get_all_item_ids_on_current_page())
        while (
            self.next_page_button_locator.is_visible()
            and self.next_page_button_locator.is_enabled()
        ):
            self.next_page_button_locator.click()
            self.page.wait_for_timeout(500)
            ids.extend(self.get_all_dataset_items_on_current_page())

        return ids
