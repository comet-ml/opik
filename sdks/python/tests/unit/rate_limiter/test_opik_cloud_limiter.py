import pyrate_limiter

from opik.rate_limiter import opik_cloud_limiter


def test_opik_cloud_limiter():
    limiter = opik_cloud_limiter.OpikCloudLimiter(
        api_calls_rate=pyrate_limiter.Rate(5, pyrate_limiter.Duration.SECOND * 2),
        api_calls_max_delay_ms=10,
        ingestion_rate=pyrate_limiter.Rate(10, pyrate_limiter.Duration.SECOND * 2),
        ingestion_max_delay_ms=10,
    )

    # check API calls limiter
    for request in range(5):
        assert limiter.try_acquire_api_calls() is True

    assert limiter.try_acquire_api_calls() is False

    # check data ingestion limiter
    for request in range(10):
        assert limiter.try_acquire_ingestion() is True

    assert limiter.try_acquire_ingestion() is False


def test_opik_cloud_limiter__max_delay():
    limiter = opik_cloud_limiter.OpikCloudLimiter(
        api_calls_rate=pyrate_limiter.Rate(5, pyrate_limiter.Duration.SECOND),
        api_calls_max_delay_ms=1100,
        ingestion_rate=pyrate_limiter.Rate(10, pyrate_limiter.Duration.SECOND),
        ingestion_max_delay_ms=1100,
    )

    # check API calls limiter
    for request in range(6):
        assert limiter.try_acquire_api_calls() is True

    # check data ingestion limiter
    for request in range(11):
        assert limiter.try_acquire_ingestion() is True
