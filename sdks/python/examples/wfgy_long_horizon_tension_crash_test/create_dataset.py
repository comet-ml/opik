import os

import opik


DATASET_NAME = "wfgy_long_horizon_tension_crash_test"
THIS_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_FILE = os.path.join(THIS_DIR, "sample_dataset.jsonl")


def main() -> None:
    """
    Create (or fetch) an Opik dataset and populate it from a JSONL file.

    The JSONL file should contain one JSON object per line, with at least:
    - input: str
    - optional metadata / tags fields

    Opik will automatically deduplicate items when using the Python SDK,
    so re-running this script is safe.
    """
    # Use default Opik configuration (env vars or `opik configure`).
    client = opik.Opik()

    dataset = client.get_or_create_dataset(name=DATASET_NAME)

    if not os.path.exists(DATASET_FILE):
        raise FileNotFoundError(
            f"Dataset source file not found: {DATASET_FILE}"
        )

    dataset.read_jsonl_from_file(DATASET_FILE)

    print(f"Dataset name: {dataset.name}")
    print(f"Dataset id:   {dataset.id}")
    print(f"Loaded items from: {DATASET_FILE}")


if __name__ == "__main__":
    main()
