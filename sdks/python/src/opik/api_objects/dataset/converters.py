import json

from typing import List, Callable, Any, Dict, TYPE_CHECKING
import importlib.util
import logging

if TYPE_CHECKING:
    import pandas as pd

from . import dataset_item

ItemConstructor = Callable[[Any], dataset_item.DatasetItem]


LOGGER = logging.getLogger(__name__)
IMPORT_PANDAS_ERROR = "The Python library Pandas is required for this method. You can install it with `pip install pandas`."


def _raise_if_pandas_is_unavailable() -> None:
    module_spec = importlib.util.find_spec("pandas")
    if module_spec is None:
        raise ImportError(IMPORT_PANDAS_ERROR)


def to_pandas(
    items: List[dataset_item.DatasetItem], keys_mapping: Dict[str, str]
) -> "pd.DataFrame":
    _raise_if_pandas_is_unavailable()

    import pandas as pd

    new_item_dicts = []

    for item in items:
        item_content = item.get_content(include_id=True)
        new_item_dict = {
            keys_mapping.get(key, key): value for key, value in item_content.items()
        }
        new_item_dicts.append(new_item_dict)

    return pd.DataFrame(new_item_dicts)


def from_jsonl_file(
    file_path: str, keys_mapping: Dict[str, str], ignore_keys: List[str]
) -> List[dataset_item.DatasetItem]:
    items = []
    with open(file_path, "r", encoding="utf-8") as file:
        for line in file:
            json_object = line.strip()
            if json_object:  # Skip empty lines
                items.append(json.loads(json_object))

    json_str = json.dumps(items)
    return from_json(json_str, keys_mapping, ignore_keys)


def from_pandas(
    dataframe: "pd.DataFrame",
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[dataset_item.DatasetItem]:
    _raise_if_pandas_is_unavailable()

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
        item_content = item.get_content(include_id=True)
        new_item_dict = {
            keys_mapping.get(key, key): value for key, value in item_content.items()
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
