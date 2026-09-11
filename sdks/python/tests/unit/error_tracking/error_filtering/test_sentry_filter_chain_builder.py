from opik.error_tracking.error_filtering import filter_chain_builder
from opik.error_tracking import error_filtering


def test_sentry_filter_chain(
    fake_basic_hint,
    fake_error_event,
    fake_warning_event,
):
    for _ in range(filter_chain_builder.DEFAULT_ERROR_QUOTA):
        assert (
            error_filtering.sentry_filter_chain.validate(
                fake_error_event, fake_basic_hint
            )
            is True
        )

    for _ in range(filter_chain_builder.DEFAULT_WARNING_QUOTA):
        assert (
            error_filtering.sentry_filter_chain.validate(
                fake_warning_event, fake_basic_hint
            )
            is True
        )

    assert (
        error_filtering.sentry_filter_chain.validate(fake_error_event, fake_basic_hint)
        is False
    )
    assert (
        error_filtering.sentry_filter_chain.validate(
            fake_warning_event, fake_basic_hint
        )
        is False
    )
