from typing import List

from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.mcp.install import setup_mcp_server

__all__ = ["detected_host_keys", "setup_mcp_server"]


def detected_host_keys() -> List[str]:
    """Keys of the AI clients present on this machine, in priority order.

    Keys rather than display names: no prompt names the clients any more — the
    picker lists them — so the only readers left are the count that decides
    whether to ask at all, and the analytics property that says which clients
    were on offer. A key is what joins to the one the install reports back.
    """
    return [target.key for target in mcp_targets.detected_targets()]
