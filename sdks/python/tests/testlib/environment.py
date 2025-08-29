import os


def has_openai_api_key():
    """
    Checks if the OpenAI API key exists in the environment variables.

    This function verifies the presence of an API key labeled 'OPIK_API_KEY' in the
    system's environment variables and ensures it is not empty.

    Returns:
        bool: True if the API key exists and is not empty, False otherwise.
    """
    return (
        "OPIK_API_KEY" in os.environ
        and len(os.environ["OPIK_API_KEY"]) > 0
        and "OPENAI_ORG_ID" in os.environ
        and len(os.environ["OPENAI_ORG_ID"]) > 0
    )
