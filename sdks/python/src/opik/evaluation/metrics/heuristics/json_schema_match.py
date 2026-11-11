import json
from typing import Any, Dict, List, Optional, Type, Union

import pydantic

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    import jsonschema
except ImportError:
    jsonschema = None


SchemaType = Union[Dict[str, Any], str, Type[pydantic.BaseModel]]

_MAX_ERRORS_IN_REASON = 5


def _reject_non_standard_constant(constant: str) -> None:
    raise ValueError(f"{constant} is not valid JSON")


def _resolve_schema(schema: SchemaType) -> Dict[str, Any]:
    if isinstance(schema, type) and issubclass(schema, pydantic.BaseModel):
        return schema.model_json_schema()
    if isinstance(schema, str):
        try:
            schema = json.loads(schema)
        except json.JSONDecodeError as exception:
            raise MetricComputationError(
                f"JsonSchemaMatch: the schema is not valid JSON: {exception}"
            ) from exception
    if not isinstance(schema, (dict, bool)):
        raise MetricComputationError(
            "JsonSchemaMatch: the schema must be a dict, a JSON string or a "
            f"pydantic model class, got {type(schema).__name__}"
        )
    return schema  # type: ignore[return-value]


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
    holds every violation.

    A missing or invalid schema raises ``MetricComputationError`` instead of
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
        validator_class = jsonschema.validators.validator_for(
            schema, default=jsonschema.Draft202012Validator
        )
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
                return score_result.ScoreResult(
                    value=0.0,
                    name=self.name,
                    reason=f"Output is not valid JSON: {exception}",
                    metadata={"errors": [], "valid_json": False},
                )
        else:
            instance = output

        validator_class = jsonschema.validators.validator_for(
            resolved_schema, default=jsonschema.Draft202012Validator
        )
        format_checker = validator_class.FORMAT_CHECKER if self._check_formats else None
        validator = validator_class(resolved_schema, format_checker=format_checker)

        errors: List[Dict[str, str]] = [
            {"path": _format_path(error.absolute_path), "message": error.message}
            for error in sorted(
                validator.iter_errors(instance),
                key=lambda error: (list(map(str, error.absolute_path)), error.message),
            )
        ]

        if not errors:
            return score_result.ScoreResult(value=1.0, name=self.name)

        shown = "; ".join(
            f"{error['path']}: {error['message']}"
            for error in errors[:_MAX_ERRORS_IN_REASON]
        )
        more = len(errors) - _MAX_ERRORS_IN_REASON
        reason = f"Output does not match the schema ({len(errors)} errors): {shown}"
        if more > 0:
            reason += f"; and {more} more"

        return score_result.ScoreResult(
            value=0.0,
            name=self.name,
            reason=reason,
            metadata={"errors": errors, "valid_json": True},
        )
