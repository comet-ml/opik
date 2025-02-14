import pytest
from opik.validation import usage
from opik.types import UsageDict


@pytest.mark.parametrize(
    argnames="usage_dict, is_valid",
    argvalues=[
        (
            {
                "completion_tokens": 12,
                "total_tokens": 20,
                "prompt_tokens": 32,
            },
            True,
        ),
        (
            {
                "completion_tokens": "12",
                "total_tokens": 20,
                "prompt_tokens": 32,
            },
            True,
        ),
        (
            {
                "completion_tokens": "non-convertable-string",
                "total_tokens": 20,
                "prompt_tokens": 32,
            },
            False,
        ),
        (
            {
                "total_tokens": 20,
                "prompt_tokens": 32,
            },
            False,
        ),
        (
            {
                "completion_tokens": 12,
                "total_tokens": 20,
                "prompt_tokens": 32,
                "unknown_key": "anything",
            },
            True,
        ),
        ({}, False),
        ("not-even-a-dict", False),
        ([], False),
        (None, False),
    ],
)
def test_usage_validator(usage_dict, is_valid):
    tested = usage.UsageValidator(usage_dict, provider="some-provider")

    assert tested.validate().ok() is is_valid, f"Failed with {usage_dict}"

    if tested.validate().ok():
        assert tested.parsed_usage.full_usage == usage_dict
        assert set(tested.parsed_usage.supported_usage.keys()) == set(
            UsageDict.__annotations__.keys()
        )
