import importlib.util


def raise_if_pandas_is_unavailable() -> None:
    if importlib.util.find_spec("pandas") is None:
        raise ImportError(
            "The Python library Pandas is required for this method. "
            "You can install it with `pip install pandas`."
        )
