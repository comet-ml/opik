from opik.error_tracking.error_filtering import filter_by_response_status_code


def test_filter_by_response_status_code__status_code_from_extra_dict__happyflows(
    fake_basic_hint,
    fake_error_event_with_status_code_401,
    fake_error_event_with_status_code_500,
):
    tested = filter_by_response_status_code.FilterByResponseStatusCode(
        status_codes_to_drop=[401]
    )

    assert (
        tested.process_event(fake_error_event_with_status_code_500, fake_basic_hint)
        is True
    )

    assert (
        tested.process_event(fake_error_event_with_status_code_401, fake_basic_hint)
        is False
    )


def test_filter_by_response_status_code__status_code_from_exception_attribute__happyflows(
    fake_hint_with_exception_with_status_code_401,
    fake_hint_with_exception_with_status_code_500,
    fake_error_event,
):
    tested = filter_by_response_status_code.FilterByResponseStatusCode(
        status_codes_to_drop=[401]
    )

    assert (
        tested.process_event(
            fake_error_event, fake_hint_with_exception_with_status_code_500
        )
        is True
    )

    assert (
        tested.process_event(
            fake_error_event, fake_hint_with_exception_with_status_code_401
        )
        is False
    )
