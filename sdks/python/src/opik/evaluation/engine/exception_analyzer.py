import openai
import litellm.exceptions


def is_llm_provider_rate_limit_error(exception: Exception) -> bool:
    rate_limit_error_known_types = (
        openai.RateLimitError,
        litellm.exceptions.RateLimitError,
    )

    is_rate_limit_error = isinstance(exception, rate_limit_error_known_types) or (
        hasattr(exception, "status_code") and exception.status_code == 429
    )

    return is_rate_limit_error
