"""Compression tier vocabulary shared by every entity-specific compressor.

Tiers represent how much of the original entity survives serialization:

- `FULL`: payload returned verbatim (within size cap).
- `MEDIUM`: large string fields head-truncated; structure intact.
- `SKELETON`: only structural metadata + ids; values dropped.
- `SUMMARY`: short header line, no payload — used for entities the agent
  only needs to discover (e.g. dataset names).

Backend parity: identical enum values to `CompressionTier.java`. The
ordering FULL < MEDIUM < SKELETON < SUMMARY matches the "drop more
content as we go right" reading the prompt teaches the judge.
"""

import dataclasses
import enum
from typing import Any, Dict


class CompressionTier(str, enum.Enum):
    FULL = "FULL"
    MEDIUM = "MEDIUM"
    SKELETON = "SKELETON"
    SUMMARY = "SUMMARY"


@dataclasses.dataclass(frozen=True)
class CompressionResult:
    """Output of an entity compressor: the rendered payload plus the
    tier at which it was rendered.

    The tier is surfaced back to the judge in the `read` response so the
    model can decide whether to drill into a specific path via `scan`.
    """

    payload: Dict[str, Any]
    tier: CompressionTier
