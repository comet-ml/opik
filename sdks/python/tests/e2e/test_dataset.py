import opik
import random
import string
from . import verifiers
from opik.api_objects.dataset import dataset_item


def test_create_and_populate_dataset__happyflow(opik_client: opik.Opik):
    DATASET_NAME = "e2e-tests-dataset-".join(
        random.choice(string.ascii_letters) for _ in range(6)
    )
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(DATASET_NAME, description=DESCRIPTION)

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
        name=DATASET_NAME,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
    )
