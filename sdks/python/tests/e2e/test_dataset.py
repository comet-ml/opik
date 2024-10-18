import opik
from . import verifiers
from opik.api_objects.dataset import dataset_item
from opik.api_objects import helpers


def test_create_and_populate_dataset__happyflow(
    opik_client: opik.Opik, dataset_name: str
):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_output": {"output": "Warsaw"},
            },
        ]
    )

    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of France?"},
            expected_output={"output": "Paris"},
        ),
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Germany?"},
            expected_output={"output": "Berlin"},
        ),
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Poland?"},
            expected_output={"output": "Warsaw"},
        ),
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
    )


def test_insert_and_update_item__dataset_size_should_be_the_same__an_item_with_the_same_id_should_have_new_content(
    opik_client: opik.Opik, dataset_name: str
):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)

    ITEM_ID = helpers.generate_id()
    dataset.insert(
        [
            {
                "id": ITEM_ID,
                "input": {"question": "What is the of capital of France?"},
            },
        ]
    )
    dataset.update(
        [
            {
                "id": ITEM_ID,
                "input": {"question": "What is the of capital of Belarus?"},
            },
        ]
    )
    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Belarus?"},
        ),
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
    )


def test_deduplication(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    item = {
        "user_input": {"question": "What is the of capital of France?"},
        "expected_model_output": {"output": "Paris"},
    }

    # Write the dataset
    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)
    dataset.insert([item])

    # Read the dataset and insert the same item
    new_dataset = opik_client.get_dataset(dataset_name)
    new_dataset.insert([item])

    # Verify the dataset
    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=[
            dataset_item.DatasetItem(**item),
        ],
    )


def test_dataset_clearing(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
        ]
    )
    dataset.clear()

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=[],
    )
