"""Pytest plugin package entrypoint.

Pytest loads this module for ``-p opik.plugins.pytest``. Re-exporting hook
symbols from ``hooks`` ensures plugin hooks are discoverable.
"""

from .hooks import *  # noqa: F401,F403
