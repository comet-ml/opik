"""Constants for usage report module."""

# Safety limit to avoid infinite loops in pagination
MAX_PAGINATION_PAGES = 1000

# Maximum number of trace results to fetch per project when counting spans.
# This limit prevents excessive API calls for projects with very large trace counts.
# If a project has more traces than this limit, span counts may be incomplete.
MAX_TRACE_RESULTS = 10000
