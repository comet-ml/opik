from opik.api_key.opik_api_key import DELIMITER_CHAR, parse_api_key
from opik.logging_messages import (
    PARSE_API_KEY_EMPTY_EXPECTED_ATTRIBUTES,
    PARSE_API_KEY_TOO_MANY_PARTS,
)

import pytest


@pytest.mark.parametrize("raw_key", ["", None])
def test_parse_api_key__empty_key(raw_key, capture_log):
    opik_api_key = parse_api_key(raw_key)

    assert opik_api_key is None


def test_parse_api_key__one_part():
    raw_key = "some API key"
    opik_api_key = parse_api_key(raw_key)

    assert opik_api_key is not None
    assert opik_api_key.api_key == raw_key
    assert opik_api_key.short_api_key == raw_key


def test_parse_api_key__no_expected_attributes(capture_log):
    raw_key = "some API key"
    opik_api_key = parse_api_key(raw_key + DELIMITER_CHAR)

    assert opik_api_key is not None
    assert opik_api_key.api_key == raw_key
    assert opik_api_key.short_api_key == raw_key

    assert (
        PARSE_API_KEY_EMPTY_EXPECTED_ATTRIBUTES % (raw_key + DELIMITER_CHAR)
        in capture_log.messages
    )


def test_parse_api_key__too_many_parts(capture_log):
    raw_key = "some API key" + DELIMITER_CHAR + "one" + DELIMITER_CHAR + "two"
    opik_api_key = parse_api_key(raw_key)

    assert opik_api_key is None
    assert PARSE_API_KEY_TOO_MANY_PARTS % (3, raw_key) in capture_log.messages


def test_parse_api_key__happy_path__with_padding():
    # attributes: {"baseUrl": "https://www.comet.com"}
    raw_key = (
        "Et1RBc4nd1ef3LfyJvhyB34Po"
        + DELIMITER_CHAR
        + "eyJiYXNlVXJsIjoiaHR0cHM6Ly93d3cuY29tZXQuY29tIn0="
    )
    opik_api_key = parse_api_key(raw_key)

    assert opik_api_key is not None
    assert opik_api_key.api_key == raw_key
    assert opik_api_key.short_api_key == "Et1RBc4nd1ef3LfyJvhyB34Po"

    assert opik_api_key.base_url == "https://www.comet.com"
    assert opik_api_key["baseUrl"] == "https://www.comet.com"


def test_parse_api_key__happy_path__no_padding():
    # attributes: {"baseUrl": "https://www.comet.com"}
    raw_key = (
        "Et1RBc4nd1ef3LfyJvhyB34Po"
        + DELIMITER_CHAR
        + "eyJiYXNlVXJsIjoiaHR0cHM6Ly93d3cuY29tZXQuY29tIn0"
    )
    opik_api_key = parse_api_key(raw_key)

    assert opik_api_key is not None
    assert opik_api_key.api_key == raw_key
    assert opik_api_key.short_api_key == "Et1RBc4nd1ef3LfyJvhyB34Po"

    assert opik_api_key.base_url == "https://www.comet.com"
    assert opik_api_key["baseUrl"] == "https://www.comet.com"


@pytest.mark.parametrize(
    "raw_key",
    [
        "Et1RBc4nd1ef3LfyJvhyB34Po"
        + DELIMITER_CHAR
        + "eyJiYXNlVXJsIjoiaHR0cHM6Ly93d3cuY29tZXQuY29tIn0===",
        "Et1RBc4nd1ef3LfyJvhyB34Po"
        + DELIMITER_CHAR
        + "eyJiYXNlVXJsIjoiaHR0cHM6Ly93d3cuY29tZXQuY29tIn0==",
    ],
)
def test_parse_api_key__happy_path__wrong_padding(raw_key):
    # attributes: {"baseUrl": "https://www.comet.com"}
    opik_api_key = parse_api_key(raw_key)

    assert opik_api_key is not None
    assert opik_api_key.api_key == raw_key
    assert opik_api_key.short_api_key == "Et1RBc4nd1ef3LfyJvhyB34Po"

    assert opik_api_key.base_url == "https://www.comet.com"
    assert opik_api_key["baseUrl"] == "https://www.comet.com"
