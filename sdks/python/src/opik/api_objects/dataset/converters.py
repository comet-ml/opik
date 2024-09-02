import pandas as pd
import json

from typing import List, Callable, Any, Dict

from . import dataset_item

ItemConstructor = Callable[[Any], dataset_item.DatasetItem]


def to_pandas(
    items: List[dataset_item.DatasetItem], keys_mapping: Dict[str, str]
) -> pd.DataFrame:
    new_item_dicts = []

    for item in items:
        new_item_dict = {
            keys_mapping.get(key, key): value for key, value in item.__dict__.items()
        }
        new_item_dicts.append(new_item_dict)

    return pd.DataFrame(new_item_dicts)


def from_pandas(
    dataframe: pd.DataFrame,
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[dataset_item.DatasetItem]:
    result = []
    ignore_keys = [] if ignore_keys is None else ignore_keys
    for _, row in dataframe.iterrows():
        item_kwargs = {
            keys_mapping.get(key, key): value
            for key, value in row.items()
            if key not in ignore_keys
        }
        result.append(dataset_item.DatasetItem(**item_kwargs))

    return result


def to_json(items: List[dataset_item.DatasetItem], keys_mapping: Dict[str, str]) -> str:
    new_item_dicts = []

    for item in items:
        item_dict = item.__dict__
        new_item_dict = {
            keys_mapping.get(key, key): value for key, value in item_dict.items()
        }
        new_item_dicts.append(new_item_dict)

    result: str = json.dumps(new_item_dicts, indent=2)
    return result


def from_json(
    value: str, keys_mapping: Dict[str, str], ignore_keys: List[str]
) -> List[dataset_item.DatasetItem]:
    result = []
    item_dicts: List[Dict[str, Any]] = json.loads(value)

    for item_dict in item_dicts:
        item_kwargs = {
            keys_mapping.get(key, key): value
            for key, value in item_dict.items()
            if key not in ignore_keys
        }
        result.append(dataset_item.DatasetItem(**item_kwargs))

    return result
