"""Compatibility wrapper for degeneration phrases."""

from ...heuristics.conversation.degeneration.phrases import *  # type: ignore

__all__ = [
    name for name in globals().keys() if name.isupper()
]
