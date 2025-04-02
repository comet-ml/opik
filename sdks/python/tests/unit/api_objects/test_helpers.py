import pytest

from opik.api_objects import helpers


@pytest.mark.parametrize(
    "usage,metadata,create_metadata,expected",
    [
        (None, None, False, None),
        (None, {"foo": "bar"}, False, {"foo": "bar"}),
        ({}, None, False, None),
        ({"foo": "bar"}, None, True, {"usage": {"foo": "bar"}}),
    ],
)
def test_add_usage_to_metadata(usage, metadata, create_metadata, expected):
    result = helpers.add_usage_to_metadata(
        usage=usage, metadata=metadata, create_metadata=create_metadata
    )
    assert result == expected
