"""Shared argument-parsing primitives for the agentic tools.

Mirrors the backend's `ToolArgs.java` factoring: the helpers here cover
the parsing concerns every tool repeats (JSON loading, type/id pair,
required-string field, canonical error wording), while each tool keeps
its own argument schema, response envelope, and tool-specific validation.

Kept deliberately narrow — see the Javadoc on `ToolArgs.java` for the
design constraint. We are not building a general parser framework here.
"""

import dataclasses
import json
from typing import Any, Dict, Generic, Optional, Tuple, TypeVar

from .. import entity_ref

T = TypeVar("T")


@dataclasses.dataclass(frozen=True)
class Result(Generic[T]):
    """Either-style carrier: exactly one of `value` / `error` is set.

    Callers branch on `result.error is not None` and surface the error
    string through whatever envelope shape they use. The Result type
    itself doesn't know about envelopes — that's per-tool.
    """

    value: Optional[T] = None
    error: Optional[str] = None

    @classmethod
    def ok(cls, value: T) -> "Result[T]":
        return cls(value=value)

    @classmethod
    def err(cls, message: str) -> "Result[T]":
        return cls(error=message)

    def unwrap(self) -> T:
        """Return the value of an OK result; raise if called on an error.

        Type-checkers see `value` as `Optional[T]` (since the dataclass
        permits both fields to default to None), so callers that have
        already verified `error is None` would otherwise need an
        `assert` to narrow. `unwrap()` does the narrowing once here.
        Always pair with an `if result.error is not None` check
        upstream — `unwrap()` raising means caller logic is wrong, not
        that input data was bad.
        """
        if self.value is None:
            raise RuntimeError(f"Result.unwrap() on error result: {self.error}")
        return self.value


def parse_arguments_object(arguments: str) -> Result[Dict[str, Any]]:
    """Decode a tool's `arguments` string as a JSON object.

    Empty input is treated as `{}` so callers can rely on field
    presence checks rather than null-vs-empty branching. Non-dict
    payloads (lists, scalars, etc.) come back as an error so the tool
    surface stays uniform.
    """
    try:
        raw = json.loads(arguments) if arguments else {}
    except json.JSONDecodeError as exc:
        return Result.err(f"Invalid arguments JSON: {exc.msg}")
    if not isinstance(raw, dict):
        return Result.err("Arguments must be a JSON object")
    return Result.ok(raw)


def require_string(raw: Dict[str, Any], field: str) -> Result[str]:
    """Return the value of `field` as a non-empty string, or an error
    with the canonical `"Missing required '<field>'"` wording.

    Tools that test specific argument-error wording (and the prompt that
    teaches recovery) depend on this exact phrasing — see the strict
    `==` assertions in `test_search_tool.py` etc.
    """
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        return Result.err(f"Missing required '{field}'")
    return Result.ok(value)


def parse_envelope(
    arguments: str,
) -> Result[Tuple[Dict[str, Any], entity_ref.EntityRef]]:
    """Combined parse of the arguments' object plus the canonical
    `type`+`id` pair. Every tool's `_parse_arguments` opens with this
    same sequence; calling once collapses ~6 lines of error-check
    boilerplate into 3 at each call site.

    Returns `(raw_dict, ref)` on success so the tool can keep pulling
    its own fields out of `raw_dict`.
    """
    raw_result = parse_arguments_object(arguments)
    if raw_result.error is not None:
        return Result.err(raw_result.error)
    raw = raw_result.unwrap()

    ref_result = parse_entity_ref(raw)
    if ref_result.error is not None:
        return Result.err(ref_result.error)
    return Result.ok((raw, ref_result.unwrap()))


def parse_entity_ref(raw: Dict[str, Any]) -> Result[entity_ref.EntityRef]:
    """Parse the conventional `type` + `id` argument pair into an
    `EntityRef`. Both fields are required, and the type must be a
    known `EntityType` value; otherwise an error result carries the
    explanatory message.
    """
    type_result = require_string(raw, "type")
    if type_result.error is not None:
        return Result.err(type_result.error)
    id_result = require_string(raw, "id")
    if id_result.error is not None:
        return Result.err(id_result.error)

    type_value = type_result.unwrap()
    try:
        entity_type = entity_ref.EntityType(type_value)
    except ValueError:
        return Result.err(
            f"Unsupported entity type '{type_value}'. "
            f"Supported: {[t.value for t in entity_ref.EntityType]}"
        )
    return Result.ok(entity_ref.EntityRef(type=entity_type, id=id_result.unwrap()))
