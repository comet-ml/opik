import opik

def hotpot_300(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the HotpotQA dataset.
    """
    dataset_name = "hotpot_300" if not test_mode else "hotpot_300_test"

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == 300:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected 300. We recommend deleting the dataset and re-creating it.")

    