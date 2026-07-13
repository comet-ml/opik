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
    OPIK_STATUS_UPDATE_MESSAGE,
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


class _FakeScoringFailedError(RuntimeError):
    """Stand-in for opik_optimizer.core.exceptions.ScoringFailedError.

    errors.py detects the SDK's exception by class name + failed/total attributes
    (not isinstance, to avoid importing the optimizer SDK), so a same-named local
    class with those attributes exercises the exact code path.
    """

    def __init__(self, failed, total):
        self.failed = failed
        self.total = total
        super().__init__(
            f"The objective metric failed to score {failed} of {total} "
            "evaluation item(s). The judge model likely failed or returned "
            "invalid output."
        )


# Keep the class name identical to the real SDK exception — errors.py matches on
# type(exc).__name__ == "ScoringFailedError".
ScoringFailedError = _FakeScoringFailedError
ScoringFailedError.__name__ = "ScoringFailedError"


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


# --- W6: Opik REST client errors must not be mislabeled as model-provider auth ---


def test_real_opik_rest_unauthorized_maps_to_opik_status_message():
    # The generated Opik REST client raises this for a 401 when updating the run's
    # status. It must be classified as an *Opik* reachability problem, not the
    # model provider — and its low-level body ("401", "Invalid API key") must not leak.
    from opik.rest_api.errors.unauthorized_error import UnauthorizedError

    exc = UnauthorizedError(body={"message": "Invalid API key"})
    message = to_user_facing_message(exc)

    assert message == OPIK_STATUS_UPDATE_MESSAGE
    assert "Opik" in message
    assert "401" not in message
    assert "Invalid API key" not in message
    # Must NOT fall into the model-provider auth category.
    assert "model provider" not in message.lower()


def test_real_opik_rest_api_error_base_maps_to_opik_status_message():
    from opik.rest_api.core.api_error import ApiError

    exc = ApiError(status_code=403, body={"message": "forbidden"})
    message = to_user_facing_message(exc)

    assert message == OPIK_STATUS_UPDATE_MESSAGE
    assert "403" not in message
    assert "forbidden" not in message.lower()


def test_fake_opik_rest_error_matched_by_module_prefix():
    # A synthetic class spoofing the opik.rest_api module path is enough — we match
    # on the defining module, deliberately not on any auth string in the message.
    fake = type(
        "SomeRestError",
        (Exception,),
        {"__module__": "opik.rest_api.errors.some_error"},
    )
    message = to_user_facing_message(fake("boom"))
    assert message == OPIK_STATUS_UPDATE_MESSAGE


def test_non_opik_provider_auth_still_maps_to_model_provider_auth():
    # Guard against over-matching: a genuine model-provider auth error (not from
    # the opik client) must keep the model-provider message.
    message = to_user_facing_message(
        _FakeAuthenticationError("Error code: 401 - Incorrect API key provided")
    )
    assert "authenticate with the model provider" in message.lower()
    assert message != OPIK_STATUS_UPDATE_MESSAGE


# --- W12: structured-output / JSON parse failures ---


def test_json_parse_failure_is_classified():
    exc = ValueError(
        "Expecting value: line 1 column 1 (char 0) while parsing model reasoning output"
    )
    message = to_user_facing_message(exc)
    assert "unreadable response" in message.lower()
    # No low-level parser detail leaks.
    assert "char 0" not in message
    assert "line 1" not in message


def test_json_decode_error_type_is_classified():
    class _FakeJSONDecodeError(ValueError):
        pass

    message = to_user_facing_message(
        _FakeJSONDecodeError("JSONDecodeError: unterminated string")
    )
    assert "unreadable response" in message.lower()


# --- W15: killed / OOM subprocess ---


def test_out_of_memory_string_is_classified():
    exc = RuntimeError("Subprocess execution failed: process killed by signal SIGKILL")
    message = to_user_facing_message(exc)
    assert "out of memory" in message.lower()
    assert "smaller dataset" in message.lower()
    assert "SIGKILL" not in message


def test_synthesized_nonzero_exit_message_is_classified():
    # Mirrors the exact phrasing optimizer.py synthesizes when the subprocess
    # dies without emitting its own user_message/traceback.
    exc = Exception(
        "Subprocess execution failed:  — the optimizer subprocess terminated "
        "unexpectedly with a non-zero exit and may have run out of memory."
    )
    message = to_user_facing_message(exc)
    assert "out of memory" in message.lower()
    assert "Subprocess" not in message


# --- W18: scoring-failure (SDK ScoringFailedError) ---


def test_scoring_failed_error_reports_counts_without_leaking_judge_detail():
    exc = ScoringFailedError(failed=8, total=10)
    message = to_user_facing_message(exc)
    assert "8 of 10 items" in message
    assert "judge failed or returned invalid output" in message.lower()
    assert "run it again" in message.lower()
    # The SDK's internal wording / metric internals must not leak verbatim.
    assert "objective metric" not in message.lower()
    assert message != GENERIC_USER_MESSAGE


def test_scoring_failed_error_without_counts_degrades_gracefully():
    exc = ScoringFailedError(failed=None, total=None)
    message = to_user_facing_message(exc)
    assert "some items" in message.lower()
    assert "judge failed or returned invalid output" in message.lower()
