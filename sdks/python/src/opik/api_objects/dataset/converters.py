import json

from typing import List, Callable, Any, Dict, Iterator, TYPE_CHECKING
import logging

if TYPE_CHECKING:
    import pandas as pd

from . import dataset_item
from . import helpers

ItemConstructor = Callable[[Any], dataset_item.DatasetItem]


LOGGER = logging.getLogger(__name__)


def to_pandas(
    items: List[dataset_item.DatasetItem], keys_mapping: Dict[str, str]
) -> "pd.DataFrame":
    helpers.raise_if_pandas_is_unavailable()

    import pandas as pd

    new_item_dicts = []

    for item in items:
        item_content = item.get_content(include_id=True)
        new_item_dict = {
            keys_mapping.get(key, key): value for key, value in item_content.items()
        }
        new_item_dicts.append(new_item_dict)

    return pd.DataFrame(new_item_dicts)


def _item_from_dict(
    item_dict: Dict[str, Any], keys_mapping: Dict[str, str], ignore_keys: List[str]
) -> dataset_item.DatasetItem:
    item_kwargs = {
        keys_mapping.get(key, key): value
        for key, value in item_dict.items()
        if key not in ignore_keys
    }
    return dataset_item.DatasetItem(**item_kwargs)


def stream_from_jsonl_file(
    file_path: str, keys_mapping: Dict[str, str], ignore_keys: List[str]
) -> Iterator[dataset_item.DatasetItem]:
    """Yield one item per line, holding only the current line in memory.

    A malformed line therefore surfaces partway through the file rather than before the
    first item is produced; that is the trade for not loading the whole file.
    """
    with open(file_path, "r", encoding="utf-8") as file:
        for line in file:
            json_object = line.strip()
            if json_object:  # Skip empty lines
                yield _item_from_dict(
                    json.loads(json_object), keys_mapping, ignore_keys
                )


def from_jsonl_file(
    file_path: str, keys_mapping: Dict[str, str], ignore_keys: List[str]
) -> List[dataset_item.DatasetItem]:
    return list(stream_from_jsonl_file(file_path, keys_mapping, ignore_keys))


def from_pandas(
    dataframe: "pd.DataFrame",
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[dataset_item.DatasetItem]:
    helpers.raise_if_pandas_is_unavailable()

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
    item_dicts: List[Dict[str, Any]] = json.loads(value)
    return [
        _item_from_dict(item_dict, keys_mapping, ignore_keys)
        for item_dict in item_dicts
    ]
