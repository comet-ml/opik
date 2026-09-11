"""Type-agnostic 2-tier compressor.

Used by every non-trace entity (span, dataset_item, project, thread).
- FULL — payload returned verbatim when estimate < `FULL_TOKEN_LIMIT`.
- MEDIUM — same tree, but oversized strings replaced via path-aware
  truncator with `scan`-path hints in the suffix.

`forced_tier` from the caller is honored within the FULL/MEDIUM ladder;
SKELETON / SUMMARY requests collapse to MEDIUM (same as backend
`GenericCompressor.java`).
"""

from typing import Any, Dict, Optional

from . import path_aware_truncator, tier as tier_module, tokens

FULL_TOKEN_LIMIT = 8_000
STRING_TRUNCATION_LENGTH = 1_000


def compress(
    entity_json: Dict[str, Any],
    forced_tier: Optional[tier_module.CompressionTier] = None,
) -> tier_module.CompressionResult:
    tier = _pick_tier(entity_json, forced_tier)
    if tier is tier_module.CompressionTier.FULL:
        payload = entity_json
    else:
        payload = path_aware_truncator.truncate_strings(
            entity_json, STRING_TRUNCATION_LENGTH
        )
    return tier_module.CompressionResult(payload=payload, tier=tier)


def _pick_tier(
    entity_json: Dict[str, Any], forced: Optional[tier_module.CompressionTier]
) -> tier_module.CompressionTier:
    if forced is None:
        estimate = tokens.estimate_tokens(entity_json)
        return (
            tier_module.CompressionTier.FULL
            if estimate < FULL_TOKEN_LIMIT
            else tier_module.CompressionTier.MEDIUM
        )
    if forced is tier_module.CompressionTier.FULL:
        return tier_module.CompressionTier.FULL
    return tier_module.CompressionTier.MEDIUM
