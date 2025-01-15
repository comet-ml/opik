from typing import List
from . import filter_by_count, filter_chain, event_filter

DEFAULT_ERROR_QUOTA = 25
DEFAULT_WARNING_QUOTA = 25


def build_filter_chain() -> filter_chain.FilterChain:
    filter_error_count_handler = filter_by_count.FilterByCount(
        max_count=DEFAULT_ERROR_QUOTA, level="error"
    )
    filter_warning_count_handler = filter_by_count.FilterByCount(
        max_count=DEFAULT_WARNING_QUOTA, level="warning"
    )

    chain: List[event_filter.EventFilter] = [
        filter_error_count_handler,
        filter_warning_count_handler,
    ]

    sentry_filter_chain = filter_chain.FilterChain(filters=chain)

    return sentry_filter_chain
