from typing import Any


def dummy_callable(*args, **kwargs):
    pass


class DummyClass:
    def __init__(self, *args, **kwargs) -> None:
        pass

    def __setattr__(self, __name: str, __value: Any) -> None:
        pass

    def __getattribute__(self, __name: str) -> None:
        return dummy_callable

    def __enter__(self) -> "DummyClass":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:  # type: ignore
        pass
