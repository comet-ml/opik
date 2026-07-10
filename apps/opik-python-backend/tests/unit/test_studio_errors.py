"""Unit tests for user-facing error classification (OPIK-7159).

The optimizer worker turns a failure into a high-level, user-facing message at
the source (where the real exception type is available). These tests verify,
deterministically and offline, that:

- Studio's own typed errors produce clean, actionable messages (no low-level
  "Original error: ..." leakage),
- common third-party failures (rate limit, auth, timeout, connection, model)
  map to friendly categories,
- anything unrecognized falls back to a generic message and never leaks the
  raw exception text.
"""

from opik_backend.studio.errors import (
    GENERIC_USER_MESSAGE,
    to_user_facing_message,
)
from opik_backend.studio.exceptions import (
    DatasetNotFoundError,
    EmptyDatasetError,
    InvalidMetricError,
    JobMessageParseError,
)


class _FakeRateLimitError(Exception):
    pass


class _FakeAuthenticationError(Exception):
    pass


def test_empty_dataset_error_is_clean_and_actionable():
    message = to_user_facing_message(EmptyDatasetError("my-dataset"))
    assert "my-dataset" in message
    assert "empty" in message.lower()


def test_dataset_not_found_does_not_leak_original_error():
    exc = DatasetNotFoundError(
        "my-dataset", original_error=ValueError("clickhouse: connection refused")
    )
    message = to_user_facing_message(exc)
    assert "my-dataset" in message
    # The low-level "Original error: ..." suffix must not reach the user.
    assert "connection refused" not in message
    assert "Original error" not in message


def test_invalid_metric_error_mentions_the_metric():
    message = to_user_facing_message(InvalidMetricError("equals", "bad params"))
    assert "equals" in message
    assert "metric" in message.lower()


def test_job_message_parse_error_is_high_level():
    message = to_user_facing_message(JobMessageParseError("missing field"))
    assert "missing field" not in message
    assert message  # non-empty, curated


def test_rate_limit_is_classified():
    message = to_user_facing_message(
        _FakeRateLimitError("Error code: 429 - rate limit exceeded")
    )
    assert "rate-limit" in message.lower()


def test_authentication_is_classified():
    message = to_user_facing_message(
        _FakeAuthenticationError("Incorrect API key provided")
    )
    assert "authenticate" in message.lower()


def test_unrecognized_error_falls_back_to_generic_without_leaking():
    exc = RuntimeError("segfault in libfoo at 0xdeadbeef")
    message = to_user_facing_message(exc)
    assert message == GENERIC_USER_MESSAGE
    assert "0xdeadbeef" not in message
