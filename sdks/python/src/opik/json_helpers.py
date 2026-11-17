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

import enum
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


def _snapshot_default_result(value: Any) -> Any:
    """Own the JSON containers without copying opaque leaves or calling copy hooks.

    orjson visits Enum values natively, so they must be captured too. An explicit
    stack preserves shared/cyclic containers without adding a Python recursion limit.
    """
    copies: dict[int, Any] = {}
    pending: list[tuple[Any, Any]] = []

    def shell(member: Any) -> Any:
        while isinstance(member, enum.Enum):
            member = member.value
        identity = id(member)
        if identity in copies:
            return copies[identity]
        if isinstance(member, dict):
            copied: Any = {}
        elif isinstance(member, (list, tuple)):
            copied = []
        else:
            return member
        copies[identity] = copied
        pending.append((member, copied))
        return copied

    result = shell(value)
    while pending:
        original, copied = pending.pop()
        if isinstance(original, dict):
            for key, member in dict.items(original):
                copied[key] = shell(member)
        else:
            members = (
                list.__iter__(original)
                if isinstance(original, list)
                else tuple.__iter__(original)
            )
            copied.extend(shell(member) for member in members)
    return result


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

    # orjson reports two very different things as `TypeError`: its own refusal of a
    # value it cannot represent, and a failure inside `default` -- which it re-raises as
    # a generic "Type is not JSON serializable", losing the caller's exception. Only the
    # first is worth retrying, so record what `default` raised and use that to tell them
    # apart. Matching on orjson's message would do the same job until orjson reworded it.
    raised_by_default: List[BaseException] = []
    # Keep the originals alive so identities cannot be reused, and retain every
    # occurrence: the same object can legitimately produce different results.
    converted: dict[int, tuple[Any, list[Any]]] = {}

    def guarded_default(unencodable: Any) -> Any:
        if default is None:
            raise TypeError(
                f"Object of type {type(unencodable).__name__} is not JSON serializable"
            )
        try:
            result = _snapshot_default_result(default(unencodable))
        except BaseException as exception:
            raised_by_default.append(exception)
            raise
        identity = id(unencodable)
        if identity not in converted:
            converted[identity] = (unencodable, [])
        converted[identity][1].append(result)
        return result

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
        pending = {key: iter(results) for key, (_, results) in converted.items()}

        def replay_default(unencodable: Any) -> Any:
            previous = pending.get(id(unencodable))
            if previous is not None:
                try:
                    return next(previous)
                except StopIteration:
                    pass
            # stdlib also visits values orjson handles natively (e.g. UUIDs), or
            # occurrences after the refusal. Those still need their first conversion.
            assert default is not None
            return default(unencodable)

        return json.dumps(
            value,
            default=replay_default if default is not None else None,
            sort_keys=sort_keys,
        ).encode("utf-8")


def loads(data: Any) -> Any:
    """Parse JSON from bytes or str, using orjson where it is installed."""
    if _orjson is not None:
        return _orjson.loads(data)
    return json.loads(data)
