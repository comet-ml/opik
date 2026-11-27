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
    *,
    default: Optional[Callable[[Any], Any]],
    sort_keys: bool,
) -> bytes:
    """Encode one value as UTF-8 JSON bytes.

    `default` receives whatever the encoder cannot represent and is expected to return
    something it can, or raise; pass None where the caller wants the encoder's own
    refusal. `sort_keys` orders mapping keys, which a caller needs when the bytes are
    hashed rather than sent.

    Both are required, and keyword-only so a call site cannot be misread. This is
    internal and its two callers want opposite things -- the wire path wants the
    flexible encoder and insertion order, the digest path wants sorted keys -- so a
    default here would quietly decide for whichever call site forgot to say. Defaults
    belong further up, where a user or a config source sets them.
    """
    if _orjson is None:
        return json.dumps(value, default=default, sort_keys=sort_keys).encode("utf-8")

    options = _SORTED_OPTIONS if sort_keys else _BASE_OPTIONS
    default_depth = 0
    used_default = False

    def encode(current: Any) -> bytes:
        nonlocal used_default
        converted: dict[int, tuple[Any, list[bytes]]] = {}
        raised_by_default: List[BaseException] = []

        def guarded_default(unencodable: Any) -> Any:
            nonlocal default_depth, used_default
            if default is None:
                raise TypeError(
                    f"Object of type {type(unencodable).__name__} is not JSON serializable"
                )
            identity = id(unencodable)
            default_depth += 1
            try:
                used_default = True
                result = default(unencodable)
                # orjson allows the initial call plus 254 recursive conversions.
                # Keep its stdlib fallback rather than adding a rejection limit.
                if default_depth >= 255:
                    encoded = json.dumps(
                        result, default=default, sort_keys=sort_keys
                    ).encode("utf-8")
                else:
                    encoded = encode(result)
            except BaseException as exception:
                raised_by_default.append(exception)
                raise
            finally:
                default_depth -= 1
            # Bytes capture mutable results with the encoder's own type dispatch.
            # Retain the original to prevent identity reuse, and every occurrence.
            if identity not in converted:
                converted[identity] = (unencodable, [])
            converted[identity][1].append(encoded)
            return _orjson.Fragment(encoded)

        def fallback() -> bytes:
            pending = {key: iter(results) for key, (_, results) in converted.items()}

            def replay_default(unencodable: Any) -> Any:
                previous = pending.get(id(unencodable))
                if previous is not None:
                    try:
                        return json.loads(next(previous))
                    except StopIteration:
                        pass
                assert default is not None
                return default(unencodable)

            return json.dumps(
                current,
                default=replay_default if default is not None else None,
                sort_keys=sort_keys,
            ).encode("utf-8")

        try:
            return _orjson.dumps(current, default=guarded_default, option=options)
        except TypeError:
            if raised_by_default:
                raise raised_by_default[0]
            return fallback()

    encoded = encode(value)
    if not used_default:
        return encoded

    # A fragment can contain a stdlib fallback (e.g. a large integer). Encode the
    # completed JSON value once in the mode its JSON-native readback would use, so
    # typed dataset items and their stored form keep the same content hash.
    normalized = json.loads(encoded)
    try:
        return _orjson.dumps(normalized, option=options)
    except TypeError:
        return json.dumps(normalized, sort_keys=sort_keys).encode("utf-8")


def loads(data: Any) -> Any:
    """Parse JSON from bytes or str, using orjson where it is installed."""
    if _orjson is not None:
        return _orjson.loads(data)
    return json.loads(data)
