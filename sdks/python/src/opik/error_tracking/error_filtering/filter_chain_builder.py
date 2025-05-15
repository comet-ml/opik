from typing import List
from . import (
    filter_by_count,
    filter_chain,
    event_filter,
    filter_by_response_status_code,
)

DEFAULT_ERROR_QUOTA = 30
DEFAULT_WARNING_QUOTA = 10


def build_filter_chain() -> filter_chain.FilterChain:
    filter_error_count_handler = filter_by_count.FilterByCount(
        max_count=DEFAULT_ERROR_QUOTA, level="error"
    )
    filter_warning_count_handler = filter_by_count.FilterByCount(
        max_count=DEFAULT_WARNING_QUOTA, level="warning"
    )
    filter_by_response_status_code_handler = (
        filter_by_response_status_code.FilterByResponseStatusCode(
            status_codes_to_drop=[400, 401, 402, 403]
        )
    )

    chain: List[event_filter.EventFilter] = [
        filter_error_count_handler,
        filter_warning_count_handler,
        filter_by_response_status_code_handler,
    ]

    sentry_filter_chain = filter_chain.FilterChain(filters=chain)

    return sentry_filter_chain
