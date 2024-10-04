import opik
from . import verifiers
from opik.api_objects.dataset import dataset_item


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


def test_deduplication(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    item = {
        "input": {"question": "What is the of capital of France?"},
        "expected_output": {"output": "Paris"},
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
