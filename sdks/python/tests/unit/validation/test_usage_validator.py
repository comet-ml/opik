import sys
from typing import get_origin

if sys.version_info < (3, 11):
    from typing_extensions import NotRequired
else:
    from typing import NotRequired

if sys.version_info < (3, 10):
    from typing import get_type_hints as get_annotations
else:
    from inspect import get_annotations

import pytest

from opik.types import UsageDict
from opik.validation import usage


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
                "completion_tokens": 12,
                "completion_tokens_details": {},
                "total_tokens": 20,
                "prompt_tokens": 32,
                "prompt_tokens_details": {},
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

        usage_dict_type_hints = get_annotations(UsageDict)
        supported_usage_keys = set(tested.parsed_usage.supported_usage.keys())

        usage_dict_all_keys = set(usage_dict_type_hints.keys())
        usage_dict_keys_without_no_required = {
            key
            for key, value in usage_dict_type_hints.items()
            if get_origin(value) is not NotRequired
        }

        assert supported_usage_keys in [
            usage_dict_all_keys,
            usage_dict_keys_without_no_required,
        ]
