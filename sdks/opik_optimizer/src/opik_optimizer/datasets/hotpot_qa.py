import opik
from importlib.resources import files
import json


def hotpot_300(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the HotpotQA dataset.
    """
    dataset_name = "hotpot_300" if not test_mode else "hotpot_300_test"
    nb_items = 300 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        # Load data from file and insert into the dataset
        json_content = (files('opik_optimizer') / 'data' / 'hotpot-500.json').read_text(encoding='utf-8')
        all_data = json.loads(json_content)
        trainset = all_data[:nb_items]

        data = []
        for row in reversed(trainset):
            data.append(row)

        dataset.insert(data)
        return dataset

def hotpot_500(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 500 samples of the HotpotQA dataset.
    """
    dataset_name = "hotpot_500" if not test_mode else "hotpot_500_test"
    nb_items = 500 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        # Load data from file and insert into the dataset
        json_content = (files('opik_optimizer') / 'data' / 'hotpot-500.json').read_text(encoding='utf-8')
        all_data = json.loads(json_content)
        trainset = all_data[:nb_items]

        data = []
        for row in reversed(trainset):
            data.append(row)

        dataset.insert(data)
        return dataset

    

    