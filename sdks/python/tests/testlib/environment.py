import os


def has_openai_api_key():
    """
    Checks if the OpenAI API key and organization ID exist in the environment variables.

    This function verifies the presence and non-emptiness of both the 'OPENAI_API_KEY'
    and 'OPENAI_ORG_ID' environment variables in the system's environment.

    Returns:
        bool: True if both variables exist and are not empty, False otherwise.
    """
    return (
        "OPENAI_API_KEY" in os.environ
        and len(os.environ["OPENAI_API_KEY"]) > 0
        and "OPENAI_ORG_ID" in os.environ
        and len(os.environ["OPENAI_ORG_ID"]) > 0
    )
