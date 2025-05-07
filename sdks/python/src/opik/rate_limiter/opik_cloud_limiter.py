import pyrate_limiter


class OpikCloudLimiter:
    def ___init__(
        self,
        api_calls_rate: pyrate_limiter.Rate,
        api_calls_delay_ms: int,
        ingestion_rate: pyrate_limiter.Rate,
        ingestion_delay_ms: int,
    ) -> None:
        self.api_calls_limiter = pyrate_limiter.Limiter(
            api_calls_rate, max_delay=api_calls_delay_ms
        )
        self.ingestion_limiter = pyrate_limiter.Limiter(
            ingestion_rate, max_delay=ingestion_delay_ms
        )
