"""Version information for the package."""

from importlib import metadata

__version__: str = "0.0.0+dev"
if __package__:
    __version__ = metadata.version(__package__)
