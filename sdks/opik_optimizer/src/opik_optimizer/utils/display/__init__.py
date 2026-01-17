"""Facade for display utilities (format + rich rendering)."""

from . import terminal as display_terminal  # re-export module for monkeypatch/test access
from .format import *  # noqa: F401,F403
from .terminal import *  # noqa: F401,F403
