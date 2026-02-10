import os
import json
import opik


DATASET_NAME = "wfgy_long_horizon_tension_crash_test"
DATASET_FILE = "sample_dataset.jsonl"


def load_items_from_jsonl(path: str) -> list[dict]:
    if not os.path.exists(path):
        raise FileNotFoundError(f"Dataset file not found: {path}")

    items: list[dict] = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            items.append(json.loads(line))

    return items


def main() -> None:
    print("=== WFGY long-horizon tension crash test :: create_dataset ===")
    print(f"OPIK_WORKSPACE = {os.environ.get('OPIK_WORKSPACE')}")
    print(f"Dataset name   = {DATASET_NAME}")
    print(f"Dataset file   = {DATASET_FILE}")

    # 1) Connect to Opik using env vars (OPIK_API_KEY, OPIK_WORKSPACE, OPIK_URL_OVERRIDE)
    client = opik.Opik()
    client.auth_check()
    print("Opik auth_check() OK")

    # 2) Get or create dataset
    dataset = client.get_or_create_dataset(DATASET_NAME)
    print(f"Using dataset id = {dataset.id}")

    # 3) Load items from local jsonl file
    items = load_items_from_jsonl(DATASET_FILE)
    print(f"Loaded {len(items)} items from {DATASET_FILE}")

    if not items:
        print("No items to insert, exiting.")
        return

    # 4) Insert items (Opik will de-duplicate by item id / hash)
    dataset.insert(items)
    print("Inserted items into dataset.")

    print("Done. You should now see the dataset in the Opik UI.")


if __name__ == "__main__":
    main()
