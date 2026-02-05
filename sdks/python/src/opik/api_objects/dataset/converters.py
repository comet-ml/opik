import json

from typing import List, Callable, Any, Dict, TYPE_CHECKING, Union
import importlib.util
import logging

if TYPE_CHECKING:
    import pandas as pd
    import narwhals as nw
    from narwhals.typing import IntoDataFrame, IntoFrameT

from . import dataset_item

ItemConstructor = Callable[[Any], dataset_item.DatasetItem]


LOGGER = logging.getLogger(__name__)
IMPORT_PANDAS_ERROR = "The Python library Pandas is required for this method. You can install it with `pip install pandas`."
IMPORT_NARWHALS_ERROR = "The Python library Narwhals is required for this method. You can install it with `pip install narwhals`."


def _raise_if_pandas_is_unavailable() -> None:
    module_spec = importlib.util.find_spec("pandas")
    if module_spec is None:
        raise ImportError(IMPORT_PANDAS_ERROR)


def _raise_if_narwhals_is_unavailable() -> None:
    module_spec = importlib.util.find_spec("narwhals")
    if module_spec is None:
        raise ImportError(IMPORT_NARWHALS_ERROR)


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


def from_dataframe(
    dataframe: "IntoDataFrame",
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[dataset_item.DatasetItem]:
    """
    Convert a Narwhals-compatible dataframe to a list of DatasetItem objects.

    Args:
        dataframe: Any dataframe compatible with Narwhals (pandas, Polars, DuckDB, etc.)
        keys_mapping: Dictionary that maps dataframe column names to dataset item field names
        ignore_keys: List of column names to ignore during conversion

    Returns:
        List of DatasetItem objects
    """
    _raise_if_narwhals_is_unavailable()

    import narwhals as nw

    result = []
    ignore_keys = [] if ignore_keys is None else ignore_keys

    df_nw = nw.from_native(dataframe)

    for row in df_nw.iter_rows(named=True):
        item_kwargs = {
            keys_mapping.get(key, key): value
            for key, value in row.items()
            if key not in ignore_keys
        }
        result.append(dataset_item.DatasetItem(**item_kwargs))

    return result


def to_dataframe(
    items: List[dataset_item.DatasetItem],
    keys_mapping: Dict[str, str],
    native_namespace: Union[str, None] = None,
) -> "IntoFrameT":
    """
    Convert a list of DatasetItem objects to a Narwhals-compatible dataframe.

    Args:
        items: List of DatasetItem objects to convert
        keys_mapping: Dictionary that maps item field names to dataframe column names
        native_namespace: Target dataframe library ('pandas', 'polars', 'pyarrow', etc.).
                         If None, returns a Narwhals DataFrame.

    Returns:
        A dataframe in the specified native format, or a Narwhals DataFrame if native_namespace is None
    """
    _raise_if_narwhals_is_unavailable()

    import narwhals as nw

    new_item_dicts = []

    for item in items:
        item_content = item.get_content(include_id=True)
        new_item_dict = {
            keys_mapping.get(key, key): value for key, value in item_content.items()
        }
        new_item_dicts.append(new_item_dict)

    df_nw = nw.from_dict(new_item_dicts)

    if native_namespace is None:
        return df_nw

    return nw.to_native(df_nw, pass_through=False)
