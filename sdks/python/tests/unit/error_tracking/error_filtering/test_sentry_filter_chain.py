from comet_ml.error_tracking.error_filtering import (
    DEFAULT_ERROR_QUOTA,
    DEFAULT_WARNING_QUOTA,
    sentry_filter_chain,
)


def test_sentry_filter_chain(
    throttling_hint,
    invalid_api_key_hint,
    basic_hint,
    mocked_error_event,
    mocked_warning_event,
):
    assert sentry_filter_chain.validate(mocked_error_event, throttling_hint) is False
    assert (
        sentry_filter_chain.validate(mocked_error_event, invalid_api_key_hint) is False
    )

    for i in range(DEFAULT_ERROR_QUOTA):
        assert sentry_filter_chain.validate(mocked_error_event, basic_hint) is True

    for i in range(DEFAULT_WARNING_QUOTA):
        assert sentry_filter_chain.validate(mocked_warning_event, basic_hint) is True

    assert sentry_filter_chain.validate(mocked_error_event, basic_hint) is False
    assert sentry_filter_chain.validate(mocked_warning_event, basic_hint) is False

    assert mocked_warning_event.get() == "warning"
    assert mocked_error_event.get() == "error"
