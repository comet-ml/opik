import os

import pytest


@pytest.fixture()
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")
