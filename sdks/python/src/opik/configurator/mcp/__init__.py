from typing import List

from opik.configurator.mcp import targets as mcp_targets
from opik.configurator.mcp.install import setup_mcp_server

__all__ = ["detected_host_names", "setup_mcp_server"]


def detected_host_names() -> List[str]:
    """Display names of the AI clients present on this machine.

    Used by the configurator to name what it found in the consent prompt instead
    of listing every host Opik happens to support.
    """
    return [target.display_name for target in mcp_targets.detected_targets()]
