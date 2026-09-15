"""JSON encoding for the SDK, and the one place that decides which encoder runs.

`orjson` is an optional accelerator. It encodes several times faster than the standard
library but publishes wheels for a narrower set of platforms -- no PyPy, GraalPy,
s390x, ppc64le, riscv64 or wasm -- so `setup.py` marks the dependency to the platforms
that have one and this module makes its absence a non-event. Nothing else in the SDK
needs to know which encoder answered.

The two encoders do not produce identical bytes: the standard library renders a float
below 1e-4 as `9.79e-05` where orjson writes `0.0000979`, and neither is configurable.
That is fine for both callers here, because neither compares bytes across processes --
a request body is read by the backend as JSON, and a content digest never leaves the
process that computed it (`Dataset` rebuilds `_hashes` by streaming items down and
hashing them locally). Within one process a single encoder answers every call, so a
digest is always compared against digests made the same way.
"""

import json
from typing import Any, Callable, Optional

try:
    import orjson as _orjson

    _BASE_OPTIONS = (
        # What `json.dumps` does with int/float/None mapping keys, which orjson refuses
        # outright without it.
        _orjson.OPT_NON_STR_KEYS
        # orjson renders datetime/date/time and dataclasses itself, and a type it
        # renders natively never reaches `default`. That would quietly replace the
        # SDK's own encoding -- a datetime would go out as `...05Z` where every
        # previous release sent `...05+00:00`. Passing them through keeps one encoder
        # of record for these types: the SDK's.
        | _orjson.OPT_PASSTHROUGH_DATETIME
        | _orjson.OPT_PASSTHROUGH_DATACLASS
        | _orjson.OPT_PASSTHROUGH_SUBCLASS
    )
    _SORTED_OPTIONS = _BASE_OPTIONS | _orjson.OPT_SORT_KEYS
except ImportError:  # pragma: no cover - the path taken where no orjson wheel exists
    _orjson = None  # type: ignore[assignment]
    _BASE_OPTIONS = 0
    _SORTED_OPTIONS = 0


#: Whether the fast encoder is in use. For diagnostics; behaviour does not depend on it.
ACCELERATED: bool = _orjson is not None


def dumps(
    value: Any,
    default: Optional[Callable[[Any], Any]] = None,
    sort_keys: bool = False,
) -> bytes:
    """Encode one value as UTF-8 JSON bytes.

    `default` receives whatever the encoder cannot represent and is expected to return
    something it can, or raise. `sort_keys` orders mapping keys, which a caller needs
    when the bytes are hashed rather than sent.
    """
    if _orjson is not None:
        try:
            return _orjson.dumps(
                value,
                default=default,
                option=_SORTED_OPTIONS if sort_keys else _BASE_OPTIONS,
            )
        except TypeError:
            # orjson refuses integers outside -2**63 .. 2**64-1 *before* consulting
            # `default`, so such a value cannot be intercepted -- only the whole value
            # re-encoded. Anything the standard library also refuses raises below,
            # exactly as it does without orjson installed.
            pass
    return json.dumps(value, default=default, sort_keys=sort_keys).encode("utf-8")
