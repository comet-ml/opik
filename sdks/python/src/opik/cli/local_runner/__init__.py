"""Local runner pairing flow — `opik connect` and `opik endpoint`.

Public surface is the two click commands re-exported here. Internal modules
(`_run`, `pairing`, `error_view`) are intentionally not re-exported.
"""

from .connect import connect
from .endpoint import endpoint

__all__ = ["connect", "endpoint"]
