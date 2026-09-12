def is_llm_provider_rate_limit_error(exception: Exception) -> bool:
    known_rate_limit_error_types = []

    try:
        import openai

        known_rate_limit_error_types.append(openai.RateLimitError)
    except ImportError:
        pass

    try:
        import litellm.exceptions

        known_rate_limit_error_types.append(litellm.exceptions.RateLimitError)
    except ImportError:
        pass

    is_rate_limit_error = isinstance(
        exception, tuple(known_rate_limit_error_types)
    ) or (hasattr(exception, "status_code") and exception.status_code == 429)

    return is_rate_limit_error
