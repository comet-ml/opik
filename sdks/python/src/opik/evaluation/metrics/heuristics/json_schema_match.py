import functools
import itertools
import json
from typing import Any, Dict, FrozenSet, List, Optional, Type, Union

import pydantic

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.metrics.heuristics.is_json import _reject_non_standard_constant

try:
    import jsonschema
except ImportError:
    jsonschema = None


SchemaType = Union[Dict[str, Any], str, Type[pydantic.BaseModel]]
# A JSON Schema is an object or, since Draft 6, a boolean.
ResolvedSchema = Union[Dict[str, Any], bool]

_MAX_ERRORS_IN_REASON = 5
# At most this many violations are kept, so a huge invalid output cannot
# exhaust memory. One more is read only to tell whether the list was cut, which
# `metadata["truncated"]` reports.
_MAX_ERRORS_COLLECTED = 100


def _resolve_schema(schema: SchemaType) -> ResolvedSchema:
    if isinstance(schema, type) and issubclass(schema, pydantic.BaseModel):
        return schema.model_json_schema()
    if isinstance(schema, str):
        try:
            schema = json.loads(schema, parse_constant=_reject_non_standard_constant)
        except ValueError as exception:
            raise MetricComputationError(
                f"JsonSchemaMatch: the schema is not valid JSON: {exception}"
            ) from exception
    if not isinstance(schema, (dict, bool)):
        raise MetricComputationError(
            "JsonSchemaMatch: the schema must be a dict, a JSON string or a "
            f"pydantic model class, got {type(schema).__name__}"
        )
    return schema


@functools.lru_cache(maxsize=1)
def _known_meta_schema_ids() -> FrozenSet[str]:
    ids = set()
    for validator_class in (
        jsonschema.Draft3Validator,
        jsonschema.Draft4Validator,
        jsonschema.Draft6Validator,
        jsonschema.Draft7Validator,
        jsonschema.Draft201909Validator,
        jsonschema.Draft202012Validator,
    ):
        meta_schema = validator_class.META_SCHEMA
        meta_schema_id = meta_schema.get("$id", meta_schema.get("id", ""))
        ids.add(_strip_empty_fragment(meta_schema_id))
    return frozenset(ids)


def _strip_empty_fragment(uri: str) -> str:
    # "...schema#" and "...schema" name the same dialect; "...schema##" does not.
    return uri[:-1] if uri.endswith("#") else uri


def _validator_class_for(schema: Any) -> Any:
    """Pick the validator for ``$schema``, Draft 2020-12 when it is absent.

    ``jsonschema`` silently falls back to its latest draft for an unknown
    ``$schema``, which would validate under a draft the author did not ask for.
    """
    if isinstance(schema, dict) and "$schema" in schema:
        dialect = schema["$schema"]
        if not isinstance(dialect, str) or (
            _strip_empty_fragment(dialect) not in _known_meta_schema_ids()
        ):
            raise MetricComputationError(
                f"JsonSchemaMatch: unsupported $schema {dialect!r}"
            )
    return jsonschema.validators.validator_for(
        schema, default=jsonschema.Draft202012Validator
    )


def _to_json_value(output: Any) -> Any:
    """Return ``output`` as a JSON value, or raise ``ValueError``.

    A string is parsed as JSON. Any other value goes through a JSON round trip,
    so it is held to the same rules as a string: ``NaN``/``Infinity`` (also as
    dict keys), circular references and values JSON cannot represent are
    rejected, and tuples become lists. Nesting too deep for the ``json`` module
    is reported the same way instead of escaping as ``RecursionError``.
    """
    try:
        if isinstance(output, (str, bytes, bytearray)):
            return json.loads(output, parse_constant=_reject_non_standard_constant)
        return json.loads(json.dumps(output, allow_nan=False))
    except RecursionError as exception:
        raise ValueError("it is nested too deeply to check") from exception
    except TypeError as exception:
        raise ValueError(str(exception)) from exception


def _format_path(path: Any) -> str:
    """Format a JSON path; keys that are not plain identifiers are quoted."""
    result = "$"
    for part in path:
        if isinstance(part, int):
            result += f"[{part}]"
        elif isinstance(part, str) and part.isidentifier():
            result += f".{part}"
        else:
            result += f"[{json.dumps(part)}]"
    return result


# Formats each draft defines, keyed by the name of its validator class.
# ``jsonschema`` checks some of them only when an optional package is installed
# (for example ``date-time`` needs ``rfc3339-validator``) and otherwise skips
# them without a word. Any other format, including one that only another draft
# defines (``color`` under Draft 2020-12, ``uuid`` under Draft 4), is a custom
# annotation for that draft, which the specification says to ignore.
_DRAFT3_FORMATS = frozenset(
    {
        "date-time",
        "date",
        "time",
        "regex",
        "color",
        "uri",
        "email",
        "ip-address",
        "ipv6",
        "host-name",
    }
)
_DRAFT4_FORMATS = frozenset({"date-time", "email", "hostname", "ipv4", "ipv6", "uri"})
_DRAFT6_FORMATS = _DRAFT4_FORMATS | {"uri-reference", "uri-template", "json-pointer"}
_DRAFT7_FORMATS = _DRAFT6_FORMATS | {
    "date",
    "time",
    "idn-email",
    "idn-hostname",
    "iri",
    "iri-reference",
    "relative-json-pointer",
    "regex",
}
_DRAFT2019_FORMATS = _DRAFT7_FORMATS | {"duration", "uuid"}
_STANDARD_FORMATS_BY_DRAFT: Dict[str, FrozenSet[str]] = {
    "Draft3Validator": _DRAFT3_FORMATS,
    "Draft4Validator": _DRAFT4_FORMATS,
    "Draft6Validator": _DRAFT6_FORMATS,
    "Draft7Validator": _DRAFT7_FORMATS,
    "Draft201909Validator": _DRAFT2019_FORMATS,
    "Draft202012Validator": _DRAFT2019_FORMATS,
}
# Keywords whose values are data, not subschemas, so a "format" inside them is
# not a format assertion.
_DATA_KEYWORDS = frozenset({"const", "enum", "default", "examples"})
# Keywords whose values map names to subschemas, so a key such as "default"
# inside them is a property name, not a data keyword.
_SCHEMA_MAP_KEYWORDS = frozenset(
    {
        "properties",
        "patternProperties",
        "definitions",
        "$defs",
        "dependentSchemas",
        "dependencies",
    }
)


def _format_checker_for(validator_class: Any) -> Any:
    """Return the format checker for the validator's draft.

    ``FORMAT_CHECKER`` exists on the validator classes from jsonschema 4.5;
    older 4.x versions expose the same per-draft checkers as module attributes.
    A per-draft checker is used rather than ``jsonschema.FormatChecker()``,
    which mixes every draft's rules (it checks ``time`` with Draft 3's rule).
    """
    checker = getattr(validator_class, "FORMAT_CHECKER", None)
    if checker is None:
        draft = validator_class.__name__[: -len("Validator")].lower()
        checker = getattr(jsonschema, f"{draft}_format_checker", None)
    return checker if checker is not None else jsonschema.FormatChecker()


def _formats_used(schema: Any) -> FrozenSet[str]:
    found = set()
    stack = [schema]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if isinstance(node.get("format"), str):
                found.add(node["format"])
            for key, value in node.items():
                if key in _DATA_KEYWORDS:
                    continue
                if key in _SCHEMA_MAP_KEYWORDS and isinstance(value, dict):
                    stack.extend(value.values())
                else:
                    stack.append(value)
        elif isinstance(node, list):
            stack.extend(node)
    return frozenset(found)


class JsonSchemaMatch(base_metric.BaseMetric):
    """
    A metric that checks whether an output conforms to a JSON Schema.

    Unlike ``StructuredOutputCompliance``, which asks an LLM to judge the output,
    this metric validates it deterministically with the ``jsonschema`` library,
    so the same output always gets the same score and no model call is made.

    The output may be a JSON string or an already-parsed value (dict, list, ...).
    A string that is not standard JSON (RFC 8259) scores 0.0. The schema's
    ``$schema`` keyword selects the draft; Draft 2020-12 is used when it is absent.

    The score is 1.0 when the output is valid and 0.0 otherwise. On failure the
    reason lists the first violations with their JSON paths, and ``metadata``
    holds up to 100 of them (``metadata["truncated"]`` is True when there were
    more). An already-parsed output is held to the same JSON rules as a string:
    ``NaN``, ``Infinity``, circular references and values JSON cannot represent
    score 0.0.

    A missing or invalid schema (including an unsupported ``$schema`` or an
    unresolvable ``$ref``) raises ``MetricComputationError`` instead of
    scoring 0.0, so a broken metric setup is reported as a scoring failure rather
    than blamed on the model output.

    Args:
        schema: Default JSON Schema, as a dict, a JSON string or a pydantic model
            class. A ``schema`` passed to ``score()`` (for example, from a dataset
            item) takes precedence.
        check_formats: Whether to assert the ``format`` keyword (``email``,
            ``date-time``, ...). Defaults to False, matching the JSON Schema
            specification, where ``format`` is an annotation by default. Some
            formats need optional packages (``pip install
            "jsonschema[format-nongpl]"``); if the schema uses a format that its
            draft defines but that cannot be checked, ``MetricComputationError``
            is raised instead of skipping it. Other formats are custom
            annotations and are ignored, as the specification requires.
        name: The name of the metric. Defaults to "json_schema_match_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases
            when there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import JsonSchemaMatch
        >>> metric = JsonSchemaMatch(
        ...     schema={
        ...         "type": "object",
        ...         "properties": {"total": {"type": "number"}},
        ...         "required": ["total"],
        ...     }
        ... )
        >>> metric.score(output='{"total": 42.5}').value
        1.0
        >>> metric.score(output='{"total": "42.5"}').value
        0.0
    """

    def __init__(
        self,
        schema: Optional[SchemaType] = None,
        check_formats: bool = False,
        name: str = "json_schema_match_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if jsonschema is None:
            raise ImportError(
                "`jsonschema` is required for the JsonSchemaMatch metric. "
                "Install via `pip install jsonschema`."
            )

        self._check_formats = check_formats
        self._schema = _resolve_schema(schema) if schema is not None else None
        if self._schema is not None:
            self._check_schema(self._schema)

    def _check_schema(self, schema: ResolvedSchema) -> None:
        validator_class = _validator_class_for(schema)
        try:
            validator_class.check_schema(schema)
        except jsonschema.SchemaError as exception:
            raise MetricComputationError(
                f"JsonSchemaMatch: the schema is not a valid JSON Schema: "
                f"{exception.message}"
            ) from exception
        if self._check_formats:
            standard = _STANDARD_FORMATS_BY_DRAFT.get(
                validator_class.__name__, frozenset()
            )
            checkable = _format_checker_for(validator_class).checkers
            unchecked = sorted((_formats_used(schema) & standard) - set(checkable))
            if unchecked:
                raise MetricComputationError(
                    "JsonSchemaMatch: check_formats=True, but these formats "
                    f"cannot be checked: {', '.join(unchecked)}. Install the "
                    'optional packages with `pip install "jsonschema[format-nongpl]"` '
                    "(some formats also need a recent jsonschema)."
                )

    def score(
        self,
        output: Any,
        schema: Optional[SchemaType] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Validate the output against the JSON Schema.

        Args:
            output: The output to validate: a JSON string or a parsed value.
            schema: Optional JSON Schema overriding the one given at construction.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: 1.0 if the output conforms to the schema,
                0.0 otherwise, with the violations in ``reason`` and ``metadata``.

        Raises:
            MetricComputationError: If no schema is available, or the schema is
                not a valid JSON Schema, names an unsupported ``$schema`` or
                cannot be applied (for example an unresolvable ``$ref``).
        """
        if schema is not None:
            resolved_schema = _resolve_schema(schema)
            self._check_schema(resolved_schema)
        elif self._schema is not None:
            resolved_schema = self._schema
        else:
            raise MetricComputationError(
                "JsonSchemaMatch: no schema provided. Pass `schema` to the "
                "constructor or to `score()`."
            )

        try:
            instance = _to_json_value(output)
        except ValueError as exception:
            return self._not_json_result(str(exception))

        validator_class = _validator_class_for(resolved_schema)
        format_checker = (
            _format_checker_for(validator_class) if self._check_formats else None
        )
        validator = validator_class(resolved_schema, format_checker=format_checker)

        try:
            # One extra error is read only to tell whether the list was cut.
            collected = list(
                itertools.islice(
                    validator.iter_errors(instance), _MAX_ERRORS_COLLECTED + 1
                )
            )
        except Exception as exception:
            # For example an unresolvable "$ref", or a recursive schema that
            # exceeds the recursion limit: the schema cannot be applied, which
            # must not be reported as a fault of the output.
            raise MetricComputationError(
                f"JsonSchemaMatch: the schema could not be applied: {exception}"
            ) from exception

        truncated = len(collected) > _MAX_ERRORS_COLLECTED
        # Sorted by path so the report reads top-down; the collected subset is
        # itself deterministic, since validation order is.
        errors: List[Dict[str, str]] = [
            {"path": _format_path(error.absolute_path), "message": error.message}
            for error in sorted(
                collected[:_MAX_ERRORS_COLLECTED],
                key=lambda error: (list(map(str, error.absolute_path)), error.message),
            )
        ]

        if not errors:
            return score_result.ScoreResult(value=1.0, name=self.name)

        count = f"{len(errors)}+" if truncated else str(len(errors))
        shown = "; ".join(
            f"{error['path']}: {error['message']}"
            for error in errors[:_MAX_ERRORS_IN_REASON]
        )
        noun = "error" if count == "1" else "errors"
        reason = f"Output does not match the schema ({count} {noun}): {shown}"
        more = len(errors) - _MAX_ERRORS_IN_REASON
        if more > 0:
            reason += f"; and {more}{'+' if truncated else ''} more"

        return score_result.ScoreResult(
            value=0.0,
            name=self.name,
            reason=reason,
            metadata={"errors": errors, "valid_json": True, "truncated": truncated},
        )

    def _not_json_result(self, detail: str) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            value=0.0,
            name=self.name,
            reason=f"Output is not valid JSON: {detail}",
            metadata={"errors": [], "valid_json": False},
        )
