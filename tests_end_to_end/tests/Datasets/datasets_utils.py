import time
from opik import Opik
import json
from page_objects.DatasetsPage import DatasetsPage
from page_objects.DatasetItemsPage import DatasetItemsPage
from playwright.sync_api import Page
import opik

TEST_ITEMS = [
    {"input": "input0", "output": "output0"},
    {"input": "input1", "output": "output1"},
    {"input": "input2", "output": "output2"},
    {"input": "input3", "output": "output3"},
    {"input": "input4", "output": "output4"},
    {"input": "input5", "output": "output5"},
    {"input": "input6", "output": "output6"},
    {"input": "input7", "output": "output7"},
    {"input": "input8", "output": "output8"},
    {"input": "input9", "output": "output9"},
]


TEST_ITEMS_UPDATE = [
    {"input": "update-input0", "output": "update-output0"},
    {"input": "update-input1", "output": "update-output1"},
    {"input": "update-input2", "output": "update-output2"},
    {"input": "update-input3", "output": "update-output3"},
    {"input": "update-input4", "output": "update-output4"},
    {"input": "update-input5", "output": "update-output5"},
    {"input": "update-input6", "output": "update-output6"},
    {"input": "update-input7", "output": "update-output7"},
    {"input": "update-input8", "output": "update-output8"},
    {"input": "update-input9", "output": "update-output9"},
]


def insert_dataset_items_sdk(client: opik.Opik, dataset_name, items_list):
    dataset = client.get_dataset(dataset_name)
    dataset.insert(items_list)


def insert_dataset_items_ui(page: Page, dataset_name, items_list):
    datasets_page = DatasetsPage(page)
    datasets_page.go_to_page()
    datasets_page.select_database_by_name(dataset_name)

    dataset_items_page = DatasetItemsPage(page)

    for item in items_list:
        dataset_items_page.insert_dataset_item(json.dumps(item))
        time.sleep(0.2)


def delete_one_dataset_item_sdk(client: opik.Opik, dataset_name):
    dataset = client.get_dataset(dataset_name)
    item = dataset.get_items()[0]
    dataset.delete([item["id"]])
    return item


def delete_one_dataset_item_ui(page: Page, dataset_name):
    dataset_page = DatasetsPage(page)
    dataset_page.go_to_page()
    dataset_page.select_database_by_name(dataset_name)
    dataset_items_page = DatasetItemsPage(page)
    item = dataset_items_page.delete_first_item_on_page_and_return_content()
    return item


def wait_for_dataset_to_be_visible(client: Opik, dataset_name: str, timeout=10):
    start_time = time.time()
    dataset = None
    while time.time() - start_time < timeout:
        dataset = None
        try:
            dataset = client.get_dataset(dataset_name)
        except Exception as _:
            pass
        finally:
            if dataset:
                break
        time.sleep(0.5)

    if dataset:
        return dataset
    else:
        raise


def wait_for_number_of_items_in_dataset(
    expected_items_number: int, dataset, timeout=10
):
    expected_number_achieved = False
    start_time = time.time()
    items = []
    while time.time() - start_time < timeout:
        items = dataset.get_items()
        if len(items) == expected_items_number:
            expected_number_achieved = True
            break
        time.sleep(0.5)

    if not expected_number_achieved:
        raise AssertionError(
            f"expected to see {expected_items_number} in dataset, instead found {len(items)} after {timeout} seconds of retries"
        )


def compare_item_lists(expected: list[dict], actual: list[dict]):
    set_expected = {frozenset(d.items()) for d in expected}
    set_actual = {
        frozenset((key, val) for key, val in d.items() if "id" not in key.lower())
        for d in actual
    }

    return set_expected == set_actual


def get_updated_items(current: list[dict], update: list[dict]):
    result = [
        {"id": item2["id"], "input": item1["input"], "output": item1["output"]}
        for item1, item2 in zip(update, current)
    ]

    return result
