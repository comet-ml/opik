"""Local runner for executing agent functions via Redis job queue."""

from .entrypoint import entrypoint, get_registry

__all__ = ["entrypoint", "get_registry"]
