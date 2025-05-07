import logging

import pyrate_limiter

from opik import config

LOGGER = logging.getLogger(__name__)


class OpikCloudLimiter:
    def __init__(
        self,
        api_calls_rate: pyrate_limiter.Rate,
        api_calls_max_delay_ms: int,
        ingestion_rate: pyrate_limiter.Rate,
        ingestion_max_delay_ms: int,
    ) -> None:
        self.api_calls_limiter = pyrate_limiter.Limiter(
            api_calls_rate, max_delay=api_calls_max_delay_ms
        )
        self.ingestion_limiter = pyrate_limiter.Limiter(
            ingestion_rate, max_delay=ingestion_max_delay_ms
        )

    def try_acquire_api_calls(self) -> bool:
        try:
            self.api_calls_limiter.try_acquire("rest_api_calls")
            return True
        except pyrate_limiter.LimiterDelayException as err:
            LOGGER.debug(f"Opik Cloud REST API calls limit exceeded: {err.meta_info}")
            return False

    def try_acquire_ingestion(self) -> bool:
        try:
            self.ingestion_limiter.try_acquire("ingestion")
            return True
        except pyrate_limiter.LimiterDelayException as err:
            LOGGER.debug(f"Opik Cloud ingestion limit exceeded: {err.meta_info}")
            return False


opik_cloud_limiter = None


def get_opik_cloud_limiter() -> OpikCloudLimiter:
    global opik_cloud_limiter
    if opik_cloud_limiter is None:
        config_ = config.OpikConfig()
        opik_cloud_limiter = OpikCloudLimiter(
            api_calls_rate=pyrate_limiter.Rate(
                config_.cloud_limiter_api_calls_rate, pyrate_limiter.Duration.MINUTE
            ),
            api_calls_max_delay_ms=config_.cloud_limiter_api_calls_max_delay_ms,
            ingestion_rate=pyrate_limiter.Rate(
                config_.cloud_limiter_ingestion_rate, pyrate_limiter.Duration.MINUTE
            ),
            ingestion_max_delay_ms=config_.cloud_limiter_ingestion_max_delay_ms,
        )

    return opik_cloud_limiter
