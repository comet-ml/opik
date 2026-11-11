import itertools
import json
import math
from typing import Any, Dict, List, Optional, Set, Tuple, Type, Union

import pydantic

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    import jsonschema
except ImportError:
    jsonschema = None


SchemaType = Union[Dict[str, Any], str, Type[pydantic.BaseModel]]

_MAX_ERRORS_IN_REASON = 5
# At most this many violations are kept, so a huge invalid output cannot
# exhaust memory. One more is read only to tell whether the list was cut, which
# `metadata["truncated"]` reports.
_MAX_ERRORS_COLLECTED = 100


def _reject_non_standard_constant(constant: str) -> None:
    raise ValueError(f"{constant} is not valid JSON")


def _resolve_schema(schema: SchemaType) -> Dict[str, Any]:
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
    return schema  # type: ignore[return-value]


def _known_meta_schema_ids() -> set:
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
    return ids


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


def _find_non_finite_number(value: Any) -> Optional[str]:
    """Return the path of the first NaN/Infinity in a parsed value, if any.

    Dict keys are checked as well as values. The walk is iterative, so a deeply
    nested value cannot raise ``RecursionError``, and a container that contains
    itself is visited once. Paths are kept as parent links and only built into
    a list for the value that is reported.
    """
    # Each entry is (value, path link); a path link is (parent link, key) or None.
    stack: List[Tuple[Any, Any]] = [(value, None)]
    seen: Set[int] = set()
    while stack:
        current, link = stack.pop()
        if isinstance(current, float) and not math.isfinite(current):
            return _format_path(_unlink(link))
        if isinstance(current, (dict, list, tuple)):
            if id(current) in seen:
                continue
            seen.add(id(current))
        if isinstance(current, dict):
            children = []
            for key, item in current.items():
                if isinstance(key, float) and not math.isfinite(key):
                    return f"{_format_path(_unlink(link))} (key {key!r})"
                children.append((item, (link, key)))
            stack.extend(reversed(children))
        elif isinstance(current, (list, tuple)):
            stack.extend(
                (item, (link, index))
                for index, item in reversed(list(enumerate(current)))
            )
    return None


def _unlink(link: Any) -> List[Any]:
    path: List[Any] = []
    while link is not None:
        link, key = link
        path.append(key)
    path.reverse()
    return path


def _format_path(path: Any) -> str:
    result = "$"
    for part in path:
        result += f"[{part}]" if isinstance(part, int) else f".{part}"
    return result


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
    more). An already-parsed output containing ``NaN`` or ``Infinity`` scores 0.0,
    the same as a string containing them.

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
            specification, where ``format`` is an annotation by default.
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

    def _check_schema(self, schema: Dict[str, Any]) -> None:
        validator_class = _validator_class_for(schema)
        try:
            validator_class.check_schema(schema)
        except jsonschema.SchemaError as exception:
            raise MetricComputationError(
                f"JsonSchemaMatch: the schema is not a valid JSON Schema: "
                f"{exception.message}"
            ) from exception

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
            MetricComputationError: If no schema is available or the schema is
                not a valid JSON Schema.
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

        if isinstance(output, (str, bytes, bytearray)):
            try:
                instance = json.loads(
                    output, parse_constant=_reject_non_standard_constant
                )
            except ValueError as exception:
                return self._not_json_result(str(exception))
        else:
            instance = output
            non_finite_path = _find_non_finite_number(instance)
            if non_finite_path is not None:
                return self._not_json_result(
                    f"{non_finite_path} is NaN or Infinity, which JSON cannot represent"
                )

        validator_class = _validator_class_for(resolved_schema)
        format_checker = validator_class.FORMAT_CHECKER if self._check_formats else None
        validator = validator_class(resolved_schema, format_checker=format_checker)

        try:
            # One extra error is read only to tell whether the list was cut.
            collected = list(
                itertools.islice(
                    validator.iter_errors(instance), _MAX_ERRORS_COLLECTED + 1
                )
            )
        except Exception as exception:
            # For example an unresolvable "$ref": a problem with the schema,
            # not with the output.
            raise MetricComputationError(
                f"JsonSchemaMatch: the schema could not be applied: {exception}"
            ) from exception

        truncated = len(collected) > _MAX_ERRORS_COLLECTED
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
        reason = f"Output does not match the schema ({count} errors): {shown}"
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
