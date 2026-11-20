import pydantic
import pytest

from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.heuristics import json_schema_match
from opik.exceptions import MetricComputationError


INVOICE_SCHEMA = {
    "type": "object",
    "properties": {
        "invoice_id": {"type": "string"},
        "total": {"type": "number", "minimum": 0},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"amount": {"type": "number"}},
                "required": ["amount"],
            },
        },
    },
    "required": ["invoice_id", "total"],
    "additionalProperties": False,
}


class Invoice(pydantic.BaseModel):
    invoice_id: str
    total: float


def _metric(**kwargs) -> json_schema_match.JsonSchemaMatch:
    return json_schema_match.JsonSchemaMatch(track=False, **kwargs)


@pytest.mark.parametrize(
    "output",
    [
        '{"invoice_id": "A-1", "total": 42.5}',
        '{"invoice_id": "A-1", "total": 0, "items": [{"amount": 1}]}',
        {"invoice_id": "A-1", "total": 42.5},
    ],
)
def test_json_schema_match__valid_output__returns_score_one(output) -> None:
    result = _metric(schema=INVOICE_SCHEMA).score(output=output)

    assert isinstance(result, score_result.ScoreResult)
    assert result.name == "json_schema_match_metric"
    assert result.value == 1.0
    assert result.reason is None
    assert result.scoring_failed is False


@pytest.mark.parametrize(
    "output, expected_path",
    [
        ('{"invoice_id": "A-1", "total": "42.5"}', "$.total"),
        ('{"invoice_id": "A-1", "total": -1}', "$.total"),
        ('{"invoice_id": "A-1"}', "$"),
        ('{"invoice_id": "A-1", "total": 1, "extra": true}', "$"),
        (
            '{"invoice_id": "A-1", "total": 1, "items": [{}, {"amount": "x"}]}',
            "$.items[0]",
        ),
        ("[]", "$"),
    ],
)
def test_json_schema_match__schema_violation__returns_score_zero_with_path(
    output, expected_path
) -> None:
    result = _metric(schema=INVOICE_SCHEMA).score(output=output)

    assert result.value == 0.0
    assert result.scoring_failed is False
    assert result.metadata["valid_json"] is True
    assert expected_path in [error["path"] for error in result.metadata["errors"]]
    assert expected_path in result.reason


def test_json_schema_match__all_violations_reported__in_metadata() -> None:
    output = '{"total": "x", "items": [{"amount": "a"}, {"amount": "b"}], "z": 1}'

    result = _metric(schema=INVOICE_SCHEMA).score(output=output)

    errors = result.metadata["errors"]
    assert sorted(error["path"] for error in errors) == sorted(
        ["$", "$", "$.items[0].amount", "$.items[1].amount", "$.total"]
    )
    root_messages = [error["message"] for error in errors if error["path"] == "$"]
    assert any("'invoice_id' is a required property" in m for m in root_messages)
    assert any("Additional properties are not allowed" in m for m in root_messages)
    assert result.metadata["truncated"] is False
    assert result.reason.startswith("Output does not match the schema (5 errors)")
    assert "'invoice_id' is a required property" in result.reason
    assert "Additional properties are not allowed" in result.reason


def test_json_schema_match__many_violations__collection_is_capped() -> None:
    schema = {"type": "array", "items": {"type": "integer"}}
    output = ["x"] * 10_000

    result = _metric(schema=schema).score(output=output)

    assert result.value == 0.0
    assert len(result.metadata["errors"]) == 100
    assert result.metadata["truncated"] is True
    assert result.reason.startswith("Output does not match the schema (100+ errors)")
    assert result.reason.endswith("; and 95+ more")


@pytest.mark.parametrize(
    "output",
    [
        float("nan"),
        float("inf"),
        {"invoice_id": "A-1", "total": float("-inf")},
        {"invoice_id": "A-1", "total": 1, "items": [{"amount": float("nan")}]},
        {"a": [1, (2, float("inf"))]},
        {float("nan"): "value"},
    ],
)
def test_json_schema_match__parsed_output_with_nan_or_infinity__returns_score_zero(
    output,
) -> None:
    result = _metric(schema={"type": ["number", "object"]}).score(output=output)

    assert result.value == 0.0
    assert result.metadata["valid_json"] is False
    assert "Out of range float values are not JSON compliant" in result.reason


def test_json_schema_match__circular_output__returns_score_zero() -> None:
    output: dict = {"total": 1}
    output["self"] = output

    result = _metric(schema={"type": "object"}).score(output=output)

    assert result.value == 0.0
    assert result.metadata["valid_json"] is False
    assert "Circular reference" in result.reason


def test_json_schema_match__unserializable_output__returns_score_zero() -> None:
    result = _metric(schema={}).score(output={"when": object()})

    assert result.value == 0.0
    assert result.metadata["valid_json"] is False
    assert "not JSON serializable" in result.reason


@pytest.mark.parametrize(
    "output",
    ["[" * 100_000 + "]" * 100_000, "nested-list"],
)
def test_json_schema_match__too_deeply_nested_output__returns_score_zero(
    output,
) -> None:
    if output == "nested-list":
        output = current = []
        for _ in range(100_000):
            child: list = []
            current.append(child)
            current = child

    result = _metric(schema={"type": "array"}).score(output=output)

    assert result.value == 0.0
    assert result.reason == "Output is not valid JSON: it is nested too deeply to check"


def test_json_schema_match__tuple_output__is_checked_as_array() -> None:
    schema = {"type": "array", "items": {"type": "integer"}}

    assert _metric(schema=schema).score(output=(1, 2, 3)).value == 1.0


@pytest.mark.parametrize(
    "output",
    ["not json", '{"invoice_id": "A-1", "total": NaN}', '{"total": 1', ""],
)
def test_json_schema_match__output_not_json__returns_score_zero(output) -> None:
    result = _metric(schema=INVOICE_SCHEMA).score(output=output)

    assert result.value == 0.0
    assert result.scoring_failed is False
    assert result.metadata["valid_json"] is False
    assert result.reason.startswith("Output is not valid JSON")


def test_json_schema_match__schema_passed_to_score__overrides_default() -> None:
    metric = _metric(schema={"type": "string"})

    result = metric.score(
        output='{"invoice_id": "A-1", "total": 1}', schema=INVOICE_SCHEMA
    )

    assert result.value == 1.0


def test_json_schema_match__schema_as_json_string__is_parsed() -> None:
    result = _metric(schema='{"type": "integer"}').score(output="7")

    assert result.value == 1.0


def test_json_schema_match__pydantic_model_schema__is_used() -> None:
    metric = _metric(schema=Invoice)

    assert metric.score(output='{"invoice_id": "A-1", "total": 3}').value == 1.0
    assert metric.score(output='{"invoice_id": "A-1"}').value == 0.0


def test_json_schema_match__no_schema__raises() -> None:
    with pytest.raises(MetricComputationError, match="no schema provided"):
        _metric().score(output="{}")


@pytest.mark.parametrize(
    "schema",
    [
        {"type": "no-such-type"},
        {"minimum": "zero"},
        "{not json",
        42,
        '{"maximum": NaN}',
        {"$schema": "https://example.com/my-dialect", "type": "string"},
        {"$schema": 7},
        {"$schema": "http://json-schema.org/draft-07/schema##"},
    ],
)
def test_json_schema_match__invalid_schema__raises_instead_of_scoring_zero(
    schema,
) -> None:
    with pytest.raises(MetricComputationError):
        _metric(schema=schema)
    with pytest.raises(MetricComputationError):
        _metric().score(output="{}", schema=schema)


def test_json_schema_match__format__ignored_unless_check_formats() -> None:
    schema = {"type": "string", "format": "date"}

    assert _metric(schema=schema).score(output='"not-a-date"').value == 1.0
    assert (
        _metric(schema=schema, check_formats=True).score(output='"not-a-date"').value
        == 0.0
    )
    assert (
        _metric(schema=schema, check_formats=True).score(output='"2026-11-11"').value
        == 1.0
    )


def test_json_schema_match__draft_selected_from_dollar_schema() -> None:
    # Draft 4 uses a boolean exclusiveMaximum; Draft 2020-12 rejects that form.
    schema = {
        "$schema": "http://json-schema.org/draft-04/schema#",
        "type": "number",
        "maximum": 10,
        "exclusiveMaximum": True,
    }

    metric = _metric(schema=schema)

    assert metric.score(output="9").value == 1.0
    assert metric.score(output="10").value == 0.0


def test_json_schema_match__unresolvable_ref__raises_instead_of_leaking() -> None:
    metric = _metric(schema={"$ref": "#/$defs/missing"})

    with pytest.raises(MetricComputationError, match="could not be applied"):
        metric.score(output="{}")


@pytest.mark.parametrize(
    "dialect",
    [
        "https://json-schema.org/draft/2020-12/schema",
        "http://json-schema.org/draft-07/schema#",
        "http://json-schema.org/draft-07/schema",
    ],
)
def test_json_schema_match__known_dollar_schema__is_accepted(dialect) -> None:
    metric = _metric(schema={"$schema": dialect, "type": "integer"})

    assert metric.score(output="3").value == 1.0


def test_json_schema_match__keys_that_are_not_identifiers__are_quoted_in_paths() -> (
    None
):
    schema = {
        "type": "object",
        "properties": {
            "unit.price": {"type": "number"},
            "line items": {"type": "array"},
        },
    }

    result = _metric(schema=schema).score(output={"unit.price": "x", "line items": 1})

    paths = sorted(error["path"] for error in result.metadata["errors"])
    assert paths == ['$["line items"]', '$["unit.price"]']
