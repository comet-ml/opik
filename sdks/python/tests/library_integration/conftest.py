import os

import pytest


@pytest.fixture()
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")


@pytest.fixture
def gcp_e2e_test_credentials():
    gcp_credentials_file_name = "gcp_credentials.json"

    gcp_credentials = json.loads(os.environ["GCP_E2E_TEST_CREDENTIALS"])

    with open(gcp_credentials_file_name, mode="wt") as file:
        file.write(json.dumps(gcp_credentials))

    os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = gcp_credentials_file_name

    yield

    del os.environ["GOOGLE_APPLICATION_CREDENTIALS"]
    os.remove(gcp_credentials_file_name)
