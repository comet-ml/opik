import pathlib
import opik
from . import verifiers
from opik.api_objects.dataset import dataset_item


def test_read_json_from_file(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset from JSON file"

    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)

    # Get the absolute path to the JSON file
    data_dir = pathlib.Path(__file__).parent.parent / "data"
    json_file_path = data_dir / "dataset.jsonl"

    # Read JSON from file and insert into dataset
    dataset.read_json_from_file(json_file_path)

    # Define expected dataset items
    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"user_question": "What is the capital of France?"},
            expected_output={"assistant_answer": "The capital of France is Paris."}
        ),
        dataset_item.DatasetItem(
            input={"user_question": "How many planets are in our solar system?"},
            expected_output={"assistant_answer": "There are 8 planets in our solar system: Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus, and Neptune."}
        ),
        dataset_item.DatasetItem(
            input={"user_question": "Who wrote the play 'Romeo and Juliet'?"},
            expected_output={"assistant_answer": "William Shakespeare wrote the play 'Romeo and Juliet'."}
        ),
        dataset_item.DatasetItem(
            input={"user_question": "What is the chemical symbol for gold?"},
            expected_output={"assistant_answer": "The chemical symbol for gold is Au."}
        ),
        dataset_item.DatasetItem(
            input={"user_question": "What year did World War II end?"},
            expected_output={"assistant_answer": "World War II ended in 1945."}
        )
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
    )

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
