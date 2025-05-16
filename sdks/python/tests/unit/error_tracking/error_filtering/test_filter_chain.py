from opik.error_tracking.error_filtering import (
    filter_chain,
    filter_by_count,
    event_filter,
)


def test_custom_filter_chain(fake_error_event, fake_basic_hint):
    count_handler = filter_by_count.FilterByCount(max_count=1, level="error")

    tested_chain = filter_chain.FilterChain([count_handler])

    assert tested_chain.validate(fake_error_event, fake_basic_hint) is True
    # because of the count = 1 should be false
    assert tested_chain.validate(fake_error_event, fake_basic_hint) is False


class FakeException(Exception):
    pass


class FilterWithExceptionInProcess(event_filter.EventFilter):
    def process_event(self):
        raise FakeException()


def test_filter_chain_exception_in_process():
    exception_handler = FilterWithExceptionInProcess()
    chain = filter_chain.FilterChain([exception_handler])
    assert chain.validate(None, None) is False
