"""
Utilities for working with optimizer candidate collections.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from typing import TypeVar

__all__ = ["unique_ordered_by_key"]

T = TypeVar("T")


def unique_ordered_by_key(
    items: Iterable[T],
    key: Callable[[T], str],
    *,
    drop_keys: set[str] | None = None,
) -> list[T]:
    """
    Return a list of items that preserves the original order while removing duplicates.

    Args:
        items: Sequence of items to filter.
        key: Function that extracts the comparison key from an item.
        drop_keys: Optional set of keys to omit entirely from the result.

    Returns:
        List[T]: Ordered list containing the first occurrence of each unique key.
    """
    seen: set[str] = set()
    filtered: list[T] = []

    for item in items:
        try:
            item_key = key(item)
        except (TypeError, AttributeError, KeyError):
            # If the key extractor fails, fall back to stringifying the item.
            item_key = str(item)

        if drop_keys and item_key in drop_keys:
            seen.add(item_key)
            continue

        if item_key in seen:
            continue

        seen.add(item_key)
        filtered.append(item)

    return filtered
