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
from typing import Any, Callable, List, Optional

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
        # of record for these two: the SDK's, which handles both.
        | _orjson.OPT_PASSTHROUGH_DATETIME
        | _orjson.OPT_PASSTHROUGH_DATACLASS
        # OPT_PASSTHROUGH_SUBCLASS is deliberately NOT set. `encode_flexible` has no
        # case for a subclass of str/int/list/dict, so passing one through reaches its
        # final `raise` and fails an upload that works today -- the standard library
        # serialises them as their builtin form. Letting orjson do the same is what
        # keeps the two encoders agreeing.
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
    if _orjson is None:
        return json.dumps(value, default=default, sort_keys=sort_keys).encode("utf-8")

    # orjson reports two very different things as `TypeError`: its own refusal of a
    # value it cannot represent, and a failure inside `default` -- which it re-raises as
    # a generic "Type is not JSON serializable", losing the caller's exception. Only the
    # first is worth retrying, so record what `default` raised and use that to tell them
    # apart. Matching on orjson's message would do the same job until orjson reworded it.
    raised_by_default: List[BaseException] = []

    def guarded_default(unencodable: Any) -> Any:
        if default is None:
            raise TypeError(
                f"Object of type {type(unencodable).__name__} is not JSON serializable"
            )
        try:
            return default(unencodable)
        except BaseException as exception:
            raised_by_default.append(exception)
            raise

    try:
        return _orjson.dumps(
            value,
            default=guarded_default,
            option=_SORTED_OPTIONS if sort_keys else _BASE_OPTIONS,
        )
    except TypeError:
        if raised_by_default:
            # The caller's own error, not orjson's paraphrase of it, and `default` is
            # not run a second time -- it may not be free of side effects.
            raise raised_by_default[0]
        # orjson refuses integers outside -2**63 .. 2**64-1 *before* consulting
        # `default`, so such a value cannot be intercepted, only the whole value
        # re-encoded. The standard library takes them.
        return json.dumps(value, default=default, sort_keys=sort_keys).encode("utf-8")
